package com.example.samplefacedetection

import android.annotation.SuppressLint
import android.graphics.*
import android.media.Image
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

@SuppressLint("UnsafeOptInUsageError")
fun ImageProxy.toNv21() : ByteArray{
    val yuv = BitmapUtils.YUV420toNV21(this.image)
    return yuv
}

fun Bitmap?.cropBitmapWithRect(rect: Rect): Bitmap? {
    this ?: return null
    val hight  = rect.height() + rect.height()* 0.2.toInt()
    return try {
        val cropBitmap = Bitmap.createBitmap(
            this,
            rect.left, rect.top, rect.width(), hight
        )
        cropBitmap
    } catch (ex: Exception) {
        null
    }
}

fun Image.toBitmap(): Bitmap {
    val yBuffer = planes[0].buffer // Y
    val vuBuffer = planes[2].buffer // VU
    val ySize = yBuffer.remaining()
    val vuSize = vuBuffer.remaining()

    val nv21 = ByteArray(ySize + vuSize)

    yBuffer.get(nv21, 0, ySize)
    vuBuffer.get(nv21, ySize, vuSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 50, out)
    val imageBytes = out.toByteArray()
    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    val matrix = Matrix()
    matrix.postRotate(90F)
    val bitmapRotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    return bitmapRotated
}