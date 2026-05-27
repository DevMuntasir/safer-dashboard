package com.remoteguard.app

import android.content.Context
import android.os.Environment
import com.google.firebase.database.FirebaseDatabase
import java.io.File

object FileManager {
    fun listFiles(context: Context, path: String? = null) {
        val root = if (path == null) Environment.getExternalStorageDirectory() else File(path)
        
        if (!root.exists()) {
            val deviceId = FirebaseHelper.getDeviceId(context)
            FirebaseDatabase.getInstance().getReference("devices/$deviceId/fileList").setValue(
                listOf(mapOf("name" to "Error: Path does not exist", "path" to (path ?: ""), "isDirectory" to false, "size" to 0))
            )
            return
        }

        val files = root.listFiles()?.map {
            mapOf(
                "name" to it.name,
                "path" to it.absolutePath,
                "isDirectory" to it.isDirectory,
                "size" to it.length()
            )
        }?.sortedWith(compareBy({ (it["isDirectory"] as? Boolean) != true }, { it["name"] as? String ?: "" })) ?: emptyList()

        val deviceId = FirebaseHelper.getDeviceId(context)
        FirebaseDatabase.getInstance().getReference("devices/$deviceId/fileList").setValue(files)
    }

    fun uploadFile(context: Context, path: String) {
        val file = File(path)
        if (file.exists() && !file.isDirectory) {
            FirebaseHelper.uploadFile(context, file)
        }
    }
}
