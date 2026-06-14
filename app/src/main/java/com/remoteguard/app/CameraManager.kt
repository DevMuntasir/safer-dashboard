package com.remoteguard.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(private val context: Context) {
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var isStreaming = false
    private var lastFrameTime = 0L
    private var cameraProvider: ProcessCameraProvider? = null

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun takePhoto(lifecycleOwner: LifecycleOwner) {
        if (!checkCameraPermission()) {
            Log.e("CameraManager", "Camera permission not granted")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                val imageCapture = ImageCapture.Builder().build()
                val cameraSelector = currentCameraSelector

                cameraProvider.unbindAll()
                if (isStreaming) {
                    val analysis = imageAnalysis ?: setupAnalysis(lifecycleOwner)
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture, analysis)
                } else {
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)
                }

                imageCapture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bytes = extractJpegBytes(image)
                        if (bytes != null) {
                            Log.d("CameraManager", "Photo captured: ${bytes.size} bytes")
                            FirebaseHelper.uploadPhoto(context, bytes)
                        }
                        image.close()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CameraManager", "Photo capture error: ${exception.message}", exception)
                    }
                })
            } catch (exc: Exception) {
                Log.e("CameraManager", "takePhoto error: ${exc.message}", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun startVideoRecording(lifecycleOwner: LifecycleOwner) {
        if (recording != null) {
            Log.w("CameraManager", "Video recording already in progress")
            return
        }

        if (!checkCameraPermission()) {
            Log.e("CameraManager", "Camera permission not granted for video recording")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                val cameraSelector = currentCameraSelector

                val qualitySelector = QualitySelector.fromOrderedList(
                    listOf(Quality.SD, Quality.HD, Quality.LOWEST),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, videoCapture)

                val videoFile = File(context.cacheDir, "recording_${System.currentTimeMillis()}.mp4")
                val outputOptions = FileOutputOptions.Builder(videoFile).build()

                recording = videoCapture?.output
                    ?.prepareRecording(context, outputOptions)
                    ?.start(ContextCompat.getMainExecutor(context)) { event ->
                        if (event is VideoRecordEvent.Finalize) {
                            recording = null
                            if (!event.hasError()) {
                                Log.d("CameraManager", "Video recording completed: ${videoFile.name}")
                                FirebaseHelper.uploadVideo(context, videoFile)
                            } else {
                                Log.e("CameraManager", "Video recording failed: ${event.error}")
                                videoFile.delete()
                            }
                        }
                    }
                Log.d("CameraManager", "Video recording started")
            } catch (exc: Exception) {
                Log.e("CameraManager", "startVideoRecording error: ${exc.message}", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopVideoRecording() {
        val currentRecording = recording ?: return
        try {
            currentRecording.stop()
            recording = null
            Log.d("CameraManager", "Video recording stopped")

            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                    videoCapture = null
                } catch (exc: Exception) {
                    Log.e("CameraManager", "Error unbinding camera: ${exc.message}", exc)
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (exc: Exception) {
            Log.e("CameraManager", "stopVideoRecording error: ${exc.message}", exc)
        }
    }

    fun startStreaming(lifecycleOwner: LifecycleOwner) {
        if (isStreaming) {
            Log.w("CameraManager", "Streaming already in progress")
            return
        }

        if (!checkCameraPermission()) {
            Log.e("CameraManager", "Camera permission not granted for streaming")
            return
        }

        isStreaming = true
        Log.d("CameraManager", "Starting camera stream")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                val cameraSelector = currentCameraSelector

                imageAnalysis = setupAnalysis(lifecycleOwner)

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
                Log.d("CameraManager", "Camera stream started successfully")
            } catch (exc: Exception) {
                isStreaming = false
                Log.e("CameraManager", "startStreaming error: ${exc.message}", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun setupAnalysis(lifecycleOwner: LifecycleOwner): ImageAnalysis {
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(cameraExecutor) { image ->
            val currentTime = System.currentTimeMillis()
            // Limit to ~2 frames per second to save bandwidth and Firebase costs
            if (currentTime - lastFrameTime > 500) {
                lastFrameTime = currentTime
                val bytes = imageToJpeg(image)
                if (bytes != null) {
                    FirebaseHelper.uploadLiveFrame(context, bytes)
                }
            }
            image.close()
        }
        return analysis
    }

    fun stopStreaming() {
        isStreaming = false
        Log.d("CameraManager", "Stopping camera stream")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
                imageAnalysis = null
                Log.d("CameraManager", "Camera stream stopped")
            } catch (exc: Exception) {
                Log.e("CameraManager", "stopStreaming error: ${exc.message}", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera(lifecycleOwner: LifecycleOwner) {
        if (!checkCameraPermission()) {
            Log.e("CameraManager", "Camera permission not granted for switching")
            return
        }

        val newCamera = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        currentCameraSelector = newCamera
        Log.d("CameraManager", "Switching camera to: ${getCurrentCameraName()}")

        if (isStreaming) {
            stopStreaming()
            Handler(Looper.getMainLooper()).postDelayed({
                startStreaming(lifecycleOwner)
            }, 500)
        }

        if (recording != null) {
            stopVideoRecording()
            Handler(Looper.getMainLooper()).postDelayed({
                startVideoRecording(lifecycleOwner)
            }, 1000)
        }
    }

    fun getCurrentCameraName(): String {
        return if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "BACK" else "FRONT"
    }

    private fun imageToJpeg(image: ImageProxy): ByteArray? {
        val nv21 = yuv420888ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 70, out)
        return out.toByteArray()
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        var position = 0

        // Copy Y plane
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            for (col in 0 until width) {
                nv21[position++] = yBuffer.get()
                if (yPixelStride > 1) yBuffer.position(yBuffer.position() + yPixelStride - 1)
            }
        }

        // Copy UV planes (interleaved for NV21: V, U, V, U...)
        val uvRowStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride
        val uvWidth = width / 2
        val uvHeight = height / 2

        for (row in 0 until uvHeight) {
            vBuffer.position(row * uvRowStride)
            uBuffer.position(row * uPlane.rowStride)
            for (col in 0 until uvWidth) {
                nv21[position++] = vBuffer.get()
                nv21[position++] = uBuffer.get()
                if (uvPixelStride > 1) {
                    vBuffer.position(vBuffer.position() + uvPixelStride - 1)
                    uBuffer.position(uBuffer.position() + uPlane.pixelStride - 1)
                }
            }
        }

        return nv21
    }

    private fun extractJpegBytes(image: ImageProxy): ByteArray? {
        return if (image.format == ImageFormat.JPEG) {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            bytes
        } else {
            imageToJpeg(image)
        }
    }
}
