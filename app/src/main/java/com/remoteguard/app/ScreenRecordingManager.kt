package com.remoteguard.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ScreenRecordingManager(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var mediaProjection: MediaProjection? = null
    private var currentScreenRecordingFile: File? = null
    @Volatile
    var isRecording = false
    private val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager

    fun requestScreenCapture(activity: Activity, resultCode: Int, resultData: Intent) {
        if (isRecording) {
            Log.w("ScreenRecordingManager", "Screen recording already in progress")
            return
        }

        try {
            mediaProjection = projectionManager?.getMediaProjection(resultCode, resultData)
            if (mediaProjection == null) {
                Log.e("ScreenRecordingManager", "Failed to get media projection")
                return
            }
            startScreenRecording()
        } catch (e: Exception) {
            Log.e("ScreenRecordingManager", "Error requesting screen capture: ${e.message}", e)
        }
    }

    fun startScreenRecording() {
        if (isRecording) {
            Log.w("ScreenRecordingManager", "Screen recording already in progress")
            return
        }

        if (mediaProjection == null) {
            Log.e("ScreenRecordingManager", "MediaProjection is null, need to request screen capture first")
            return
        }

        try {
            val displayMetrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.defaultDisplay.getMetrics(displayMetrics)

            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val screenDensity = displayMetrics.densityDpi

            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val timestamp = dateFormat.format(Date())
            val file = File(context.cacheDir, "screen_record_$timestamp.mp4")
            currentScreenRecordingFile = file

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(5_000_000)
                setVideoFrameRate(30)
                setVideoSize(screenWidth, screenHeight)
                setOutputFile(file.absolutePath)
                prepare()

                val recorderSurface = this.surface
                mediaProjection?.createVirtualDisplay(
                    "ScreenRecording",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    android.media.projection.MediaProjection.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorderSurface,
                    null,
                    null
                )

                start()
            }

            mediaRecorder = recorder
            isRecording = true
            Log.d("ScreenRecordingManager", "Screen recording started: ${file.name}")
        } catch (e: Exception) {
            Log.e("ScreenRecordingManager", "startScreenRecording error: ${e.message}", e)
            currentScreenRecordingFile?.delete()
            currentScreenRecordingFile = null
            mediaProjection?.stop()
            mediaProjection = null
        }
    }

    fun stopScreenRecording(context: Context) {
        if (!isRecording) {
            Log.w("ScreenRecordingManager", "No screen recording in progress")
            return
        }

        try {
            mediaRecorder?.let {
                try {
                    it.stop()
                    it.release()
                    Log.d("ScreenRecordingManager", "Screen recording stopped")
                } catch (e: Exception) {
                    Log.e("ScreenRecordingManager", "Error stopping recorder: ${e.message}", e)
                }
            }
            mediaRecorder = null
            isRecording = false

            mediaProjection?.stop()
            mediaProjection = null

            currentScreenRecordingFile?.let { file ->
                if (file.exists()) {
                    Log.d("ScreenRecordingManager", "Uploading screen recording: ${file.name}")
                    FirebaseHelper.uploadScreenRecording(context, file)
                }
            }
        } catch (e: Exception) {
            Log.e("ScreenRecordingManager", "stopScreenRecording error: ${e.message}", e)
        } finally {
            currentScreenRecordingFile = null
        }
    }

}
