package com.example.samplefacedetection.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.WindowMetricsCalculator
import com.example.samplefacedetection.app
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


/**
 * implementation "androidx.window:window:1.1.0-alpha02"
 * def camerax_version = '1.1.0'
 * implementation "androidx.camera:camera-core:$camerax_version"
 * implementation "androidx.camera:camera-camera2:$camerax_version"
 * implementation "androidx.camera:camera-lifecycle:$camerax_version"
 * implementation "androidx.camera:camera-view:$camerax_version"
 */
class CameraController(private val view: ViewInterface) {

    interface ViewInterface {
        fun cameraLifecycleOwner(): LifecycleOwner
        fun cameraLenFacing(): Int = CameraSelector.LENS_FACING_BACK
        fun cameraPreviewView(): PreviewView
        fun cameraImageAnalysis(): Boolean = false
        fun cameraDisplayRotation(): Int = cameraPreviewView().display.rotation
        fun cameraAspectRatio(): Int {
            //return AspectRatio.RATIO_4_3
            val activity: Activity =
                cameraPreviewView().context as? Activity ?: return AspectRatio.RATIO_4_3
            val wmc = WindowMetricsCalculator.getOrCreate()

            val metrics = wmc.computeCurrentWindowMetrics(activity).bounds
            //val size = Size(640, 480)
            val previewRatio = max(metrics.width(), metrics.height()).toDouble() / min(
                metrics.width(),
                metrics.height()
            )
            if (abs(previewRatio - 4.0 / 3) <= abs(previewRatio - 16.0 / 9)) {
                return AspectRatio.RATIO_4_3
            }
            return AspectRatio.RATIO_16_9
        }

        fun viewLaunch(block: suspend CoroutineScope.() -> Unit) {
            cameraLifecycleOwner().lifecycleScope.launch(
                Dispatchers.Main,
                CoroutineStart.DEFAULT,
                block
            )
        }
    }

    interface CallBack {
        fun cameraOnImageAnalysis(imageProxy: ImageProxy) = Unit
        fun cameraOnStateChanged(state: String) = Unit
        fun cameraOnError(error: String?) = Unit
        fun cameraOnPictureTaken(bitmap: Bitmap) = Unit

    }


    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvideJob: Job? = null


    private var lensFacing: Int = view.cameraLenFacing()
    private var targetRotation: Int = 0
    private val displayManager: DisplayManager get() = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    var callback: CallBack? = null
    private var analysisExecutor: ExecutorService? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            if (id == view.cameraPreviewView().display.displayId) {
                targetRotation = view.cameraDisplayRotation()
            }
        }
    }

    init {
        displayManager.registerDisplayListener(displayListener, null)
    }

    fun start() {
        cameraProvideJob?.cancel()
        cameraProvideJob = view.cameraLifecycleOwner().lifecycleScope.launch(Dispatchers.Main) {
            try {
                // find camera
                cameraProvider =
                    withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(app).get() }

                // init camera use case
                camera = initUseCaseAndStart()
                camera?.cameraInfo?.cameraState?.observe(view.cameraLifecycleOwner()) { state ->
                    view.viewLaunch {
                        if (state.error != null) {
                            callback?.cameraOnError(state.error.toString())
                        } else {
                            callback?.cameraOnStateChanged(state.type.toString())
                        }
                    }
                }
            } catch (e: Exception) {

            }
        }
    }

    fun stop() {
        cameraProvideJob?.cancel()
        cameraProvider?.unbindAll()
        analysisExecutor?.shutdown()
        displayManager.unregisterDisplayListener(displayListener)
    }

    fun takePicture(
        onSuccess: ((Bitmap) -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null
    ) {
        val executor = Executors.newSingleThreadExecutor()
        imageCapture?.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onError(e: ImageCaptureException) {
                    executor.shutdown()
                    view.viewLaunch {
                        onError(e)
                        callback?.cameraOnError(e.message)
                    }

                }

                @SuppressLint("UnsafeOptInUsageError")
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        executor.shutdown()
                        val rotation = image.imageInfo.rotationDegrees
                        val bitmap = image.image?.toBitmap().rotate(rotation)
                            ?: throw NullPointerException("bitmap is null")
                        view.viewLaunch {
                            onSuccess?.invoke(bitmap)
                            callback?.cameraOnPictureTaken(bitmap)
                        }
                    } catch (e: Exception) {
                        view.viewLaunch {
                            onError?.invoke(e)
                            callback?.cameraOnError(e.message)
                        }
                    }
                    image.close()
                }
            })

    }

    fun switchCamera() {
        stop()
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            if (cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) == true) {
                lensFacing = CameraSelector.LENS_FACING_BACK
            }
        } else {
            if (cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) == true) {
                lensFacing = CameraSelector.LENS_FACING_FRONT
            }
        }
        start()
    }

    fun check():Boolean {
        return lensFacing == CameraSelector.LENS_FACING_FRONT
    }
    fun takePictureAndSave(
        onSuccess: ((Bitmap) -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null
    ) {
        val imageFile: File = createImageFile()
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(imageFile)
            .setMetadata(metadata)
            .build()
        val executor = Executors.newSingleThreadExecutor()
        view.cameraLifecycleOwner().lifecycleScope.launch(Dispatchers.IO) {
            imageCapture?.takePicture(
                outputOptions,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(e: ImageCaptureException) {
                        executor.shutdown()
                        view.viewLaunch {
                            onError(e)
                            callback?.cameraOnError(e.message)
                        }
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        executor.shutdown()
                        try {
                            val savedUri = output.savedUri ?: Uri.fromFile(imageFile)
                            // Bitmap type hardware
                            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                val source =
                                    ImageDecoder.createSource(app.contentResolver, savedUri)
                                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                    decoder.isMutableRequired = true
                                }
                            } else {
                                MediaStore.Images.Media.getBitmap(app.contentResolver, savedUri)
                            }
                            view.viewLaunch {
                                onSuccess?.invoke(bitmap)
                                callback?.cameraOnPictureTaken(bitmap)
                            }

                        } catch (e: Exception) {
                            view.viewLaunch {
                                onError?.invoke(e)
                                callback?.cameraOnError(e.message)
                            }

                        }
                    }
                }
            )
        }

    }


    fun Bitmap?.rotate(degrees: Int): Bitmap? {
        this ?: return null
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(this, 0, 0, this.width, this.height, matrix, true)
    }

    fun Image.toBitmap(): Bitmap? {
        val buffer = planes[0].buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.capacity())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }


    fun getCameraSelector(): CameraSelector {
        return if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    @SuppressLint("RestrictedApi")
    private fun initUseCaseAndStart(): Camera? {

        //val size = Size(640, 480)

        val aspectRatio: Int = view.cameraAspectRatio()
        val rotation: Int = view.cameraDisplayRotation()
        val size = Size(1280, 720)

        val useCases = mutableListOf<UseCase>()

        val preview = Preview.Builder()
            .setTargetAspectRatio(aspectRatio)
            .setTargetRotation(rotation)
            .setCameraSelector(getCameraSelector())
            .build()
        useCases.add(preview)

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(aspectRatio)
            .build()
        useCases.add(imageCapture!!)


        /**
         * [androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888]
         * [androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888]
         */
        if (view.cameraImageAnalysis()) {
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(aspectRatio)
                .setTargetRotation(rotation)
//                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysisExecutor = Executors.newSingleThreadExecutor()
            imageAnalysis?.setAnalyzer(analysisExecutor!!) { imageProxy: ImageProxy ->
                callback?.also {
                    it.cameraOnImageAnalysis(imageProxy)
                }

            }
            useCases.add(imageAnalysis!!)
        }

        cameraProvider?.unbindAll()
        preview?.setSurfaceProvider(view.cameraPreviewView().surfaceProvider)
        return cameraProvider?.safeBindUseCase(*useCases.toTypedArray())
    }

    private fun ProcessCameraProvider?.safeUnbind(vararg arr: UseCase?) {
        arr.filterNotNull().forEach {
            try {
                this?.unbind(it)
            } catch (e: Exception) {
                callback?.cameraOnError(e.message)
            }
        }
    }

    fun saveImage(finalBitmap: Bitmap) {
        val root = Environment.getExternalStorageDirectory().toString()
        val myDir = File("$root/Face_Detection")
        myDir.mkdirs()
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val fname = "Shutta_$timeStamp.jpg"
        val file = File(myDir, fname)
        if (file.exists()) file.delete()
        try {
            val out = FileOutputStream(file)
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            out.flush()
            out.close()
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    private fun ProcessCameraProvider?.safeBindUseCase(vararg arr: UseCase?): Camera? {
        return try {
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()
            val useCases = arr.filterNotNull().toTypedArray()
            if (useCases.isNullOrEmpty()) return null
            this?.bindToLifecycle(
                view.cameraLifecycleOwner(),
                cameraSelector,
                *useCases
            )
        } catch (e: Exception) {
            callback?.cameraOnError(e.message)
            null
        }

    }

    companion object {

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        private fun createImageFile(): File {
            val mediaDir: File? = app.externalMediaDirs.firstOrNull()?.let {
                val file = File(it, "Images")
                file.mkdirs()
                return@let file
            }
            val dir: File = if (mediaDir?.exists() == true) mediaDir else app.filesDir
            val timeFmt = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            val fileName = "${timeFmt.format(System.currentTimeMillis())}.jpg"
            return File(dir, fileName)
        }

        fun isGrantedPermission(): Boolean {
            arrayOf(Manifest.permission.CAMERA).forEach {
                if (ContextCompat.checkSelfPermission(
                        app,
                        it
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return false
                }
            }
            return true
        }

    }

}