package com.remoteguard.app

import android.app.Application
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

class RemoteGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            runCatching { FirebaseDatabase.getInstance().setPersistenceEnabled(true) }
            Log.d("RemoteGuardApp", "Application onCreate - Initializing Firebase and Cloudinary")
            FirebaseHelper.initialize(this)
        } catch (e: Exception) {
            Log.e("RemoteGuardApp", "Failed to initialize Firebase: ${e.message}", e)
        }
    }
}
