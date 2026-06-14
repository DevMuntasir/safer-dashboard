package com.remoteguard.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

class VoiceRecordingManager(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null
    @Volatile
    var isRecording = false

    private fun checkAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startAudioRecording(context: Context) {
        if (isRecording) {
            Log.w("VoiceRecordingManager", "Audio recording already in progress")
            return
        }

        if (!checkAudioPermission()) {
            Log.e("VoiceRecordingManager", "Audio recording permission not granted")
            return
        }

        val file = File(context.cacheDir, "audio_${System.currentTimeMillis()}.m4a")
        currentAudioFile = file

        try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            isRecording = true
            Log.d("VoiceRecordingManager", "Audio recording started")
        } catch (e: Exception) {
            Log.e("VoiceRecordingManager", "startAudioRecording error: ${e.message}", e)
            currentAudioFile = null
            file.delete()
        }
    }

    fun stopAudioRecording(context: Context) {
        if (!isRecording) {
            Log.w("VoiceRecordingManager", "No audio recording in progress")
            return
        }

        try {
            mediaRecorder?.let {
                try {
                    it.stop()
                    it.release()
                    Log.d("VoiceRecordingManager", "Audio recording stopped")
                } catch (e: Exception) {
                    Log.e("VoiceRecordingManager", "Error stopping recorder: ${e.message}", e)
                }
            }
            mediaRecorder = null
            isRecording = false

            currentAudioFile?.let { file ->
                if (file.exists()) {
                    Log.d("VoiceRecordingManager", "Uploading audio file: ${file.name}")
                    FirebaseHelper.uploadAudio(context, file)
                }
            }
        } catch (e: Exception) {
            Log.e("VoiceRecordingManager", "stopAudioRecording error: ${e.message}", e)
        } finally {
            currentAudioFile = null
        }
    }
}
