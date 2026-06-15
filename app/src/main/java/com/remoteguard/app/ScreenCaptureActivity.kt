package com.remoteguard.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class ScreenCaptureActivity : ComponentActivity() {

    private lateinit var screenRecordingManager: ScreenRecordingManager
    private lateinit var projectionManager: MediaProjectionManager

    private val screenCaptureResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.d("ScreenCaptureActivity", "Screen capture permission granted")
            screenRecordingManager.requestScreenCapture(
                this,
                result.resultCode,
                result.data!!
            )
        } else {
            Log.w("ScreenCaptureActivity", "Screen capture permission denied")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            screenRecordingManager = ScreenRecordingManager(this)
            projectionManager = getSystemService(MediaProjectionManager::class.java)
                ?: throw Exception("MediaProjectionManager not available")

            Log.d("ScreenCaptureActivity", "Requesting screen capture - permission already granted during onboarding")
            val intent = projectionManager.createScreenCaptureIntent()
            screenCaptureResultLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("ScreenCaptureActivity", "Error requesting screen capture: ${e.message}", e)
            FirebaseHelper.logRemote("ScreenCaptureActivity", "Screen capture error: ${e.message}", true)
            finish()
        }
    }
}
