package com.example.samplefacedetection

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.util.Xml
import android.view.View
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.samplefacedetection.camera.CameraController
import com.example.samplefacedetection.camera.GraphicOverlay
import com.example.samplefacedetection.databinding.ActivityMainBinding
import com.example.samplefacedetection.face_detection.FaceContourGraphic
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(),
    CameraController.ViewInterface,
    CameraController.CallBack {

    private lateinit var binding: ActivityMainBinding

    private val cameraController = CameraController(this)

    private val graphicOverlay: GraphicOverlay get() = binding.graphicOverlayFinder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (allPermissionsGranted()) {
            cameraController.start()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        cameraController.callback = this

        binding.btnSwitchCamera.setOnClickListener {
            graphicOverlay.toggleSelector()
            cameraController.switchCamera()
        }

//        binding.imageCaptureButton.setOnClickListener {
//            cameraController.takePictureAndSave(onSuccess = {
//               binding.imageViewCapture.setImageBitmap(it)
//            }, onError = {
//                println(it.message)
//            })
//        }
    }

    @SuppressLint("MissingSuperCall")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                cameraController.start()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    override fun cameraLifecycleOwner(): LifecycleOwner {
        return this
    }

    override fun cameraPreviewView(): PreviewView {
        return binding.viewFinder
    }

    override fun cameraImageAnalysis(): Boolean {
        return true
    }

    val realTimeOpts = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
        .build()

    val detector = FaceDetection.getClient(realTimeOpts)

    @SuppressLint("UnsafeOptInUsageError")
    override fun cameraOnImageAnalysis(imageProxy: ImageProxy) {

        /*val byte = imageProxy.toNv21()
        val width = imageProxy.width
        val height = imageProxy.height
        val rotation = imageProxy.imageInfo.rotationDegrees
        imageProxy.close()
        val image = InputImage.fromByteArray(
            byte,
            width,
            height,
            rotation,
            InputImage.IMAGE_FORMAT_NV21
        )*/

        val mediaImage = imageProxy.image
        val width = imageProxy.width
        val height = imageProxy.height
        val byte = imageProxy.toNv21()
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        detector.process(image)
            .addOnSuccessListener {
                if(it.isEmpty()){
                    graphicOverlay.clear()
                    binding.imageCaptureButton.visibility = View.INVISIBLE
                    binding.tvNoFace.visibility = View.VISIBLE
                    binding.tvWarring.visibility = View.INVISIBLE
                    binding.tvNoTakePhoto.visibility = View.INVISIBLE
                    imageProxy.close()
                } else {
                    binding.imageCaptureButton.visibility = View.VISIBLE
                    binding.tvNoFace.visibility = View.INVISIBLE
                    binding.tvNoTakePhoto.visibility = View.INVISIBLE
                    graphicOverlay.clear()
                    it.forEach { face ->
                        if (face.boundingBox.width() < 400)
                        {
                            binding.tvWarring.visibility = View.VISIBLE
                        }
                        else{
                            binding.tvWarring.visibility = View.INVISIBLE
                            binding.imageCaptureButton.setOnClickListener {
                                val bt = BitmapUtils.NV21toJPEG(byte,width,height,80)
                                val bm = BitmapFactory.decodeByteArray(bt,0,bt.size)
                                val matrix = Matrix()
                                if (cameraController.check()) {
                                    matrix.postRotate(-90F)
                                } else {
                                    matrix.postRotate(90F)
                                }
                                val bitmapRotated = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, matrix, true)
                                val cropFace = bitmapRotated.cropBitmapWithRect(face.boundingBox)
                                if (cropFace != null) {
                                    binding.imageViewCapture.setImageBitmap(cropFace)
                                    cameraController.saveImage(cropFace)
                                } else
                                {
                                    binding.tvNoTakePhoto.visibility = View.VISIBLE
                                }
                            }
                        }
                        val faceGraphic = FaceContourGraphic(graphicOverlay, face, mediaImage.cropRect)
                        graphicOverlay.add(faceGraphic)
                    }
                    graphicOverlay.postInvalidate()
                    imageProxy.close()
                }
            }
            .addOnFailureListener {
                graphicOverlay.clear()
                graphicOverlay.postInvalidate()
                Log.w("FaceDetectorProcessor", "Face Detector failed.$it")
                imageProxy.close()
            }
    }

}