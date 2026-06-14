package com.remoteguard.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase
import java.io.File

object FileManager {

    private fun checkFilePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun listFiles(context: Context, path: String? = null) {
        if (!checkFilePermission(context)) {
            Log.e("FileManager", "File access permission not granted")
            val deviceId = FirebaseHelper.getDeviceId(context)
            FirebaseDatabase.getInstance().getReference("devices/$deviceId/fileList").setValue(
                listOf(mapOf(
                    "name" to "Error: File access permission not granted",
                    "path" to (path ?: ""),
                    "isDirectory" to false,
                    "size" to 0
                ))
            )
            return
        }

        val root = if (path == null) Environment.getExternalStorageDirectory() else File(path)

        if (!root.exists()) {
            Log.e("FileManager", "Path does not exist: ${root.absolutePath}")
            val deviceId = FirebaseHelper.getDeviceId(context)
            FirebaseDatabase.getInstance().getReference("devices/$deviceId/fileList").setValue(
                listOf(mapOf(
                    "name" to "Error: Path does not exist",
                    "path" to (path ?: ""),
                    "isDirectory" to false,
                    "size" to 0
                ))
            )
            return
        }

        try {
            val files = root.listFiles()?.map {
                mapOf(
                    "name" to it.name,
                    "path" to it.absolutePath,
                    "isDirectory" to it.isDirectory,
                    "size" to it.length()
                )
            }?.sortedWith(compareBy(
                { (it["isDirectory"] as? Boolean) != true },
                { it["name"] as? String ?: "" }
            )) ?: emptyList()

            Log.d("FileManager", "Listed ${files.size} files from ${root.absolutePath}")
            val deviceId = FirebaseHelper.getDeviceId(context)
            FirebaseDatabase.getInstance().getReference("devices/$deviceId/fileList").setValue(files)
        } catch (e: Exception) {
            Log.e("FileManager", "Error listing files: ${e.message}", e)
            val deviceId = FirebaseHelper.getDeviceId(context)
            FirebaseDatabase.getInstance().getReference("devices/$deviceId/fileList").setValue(
                listOf(mapOf(
                    "name" to "Error: ${e.message}",
                    "path" to (path ?: ""),
                    "isDirectory" to false,
                    "size" to 0
                ))
            )
        }
    }

    fun uploadFile(context: Context, path: String) {
        if (!checkFilePermission(context)) {
            Log.e("FileManager", "File access permission not granted for upload")
            return
        }

        try {
            val file = File(path)
            if (!file.exists()) {
                Log.e("FileManager", "File does not exist: $path")
                return
            }
            if (file.isDirectory) {
                Log.e("FileManager", "Path is a directory, cannot upload: $path")
                return
            }
            Log.d("FileManager", "Uploading file: ${file.name}")
            FirebaseHelper.uploadFile(context, file)
        } catch (e: Exception) {
            Log.e("FileManager", "Error uploading file: ${e.message}", e)
        }
    }
}
