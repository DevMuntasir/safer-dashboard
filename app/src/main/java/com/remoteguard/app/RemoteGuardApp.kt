package com.remoteguard.app

import android.app.Application
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

class RemoteGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Log.d("RemoteGuardApp", "Application onCreate - Starting initialization")

            // Enable Firebase persistence early
            try {
                val database = FirebaseDatabase.getInstance()
                database.setPersistenceEnabled(true)
                database.goOnline()
                Log.d("RemoteGuardApp", "Firebase persistence enabled and going online")
            } catch (e: Exception) {
                Log.e("RemoteGuardApp", "Error with Firebase database setup: ${e.message}", e)
            }

            Log.d("RemoteGuardApp", "Initializing Firebase and Cloudinary")
            FirebaseHelper.initialize(this)
            Log.d("RemoteGuardApp", "Application onCreate completed")
        } catch (e: Exception) {
            Log.e("RemoteGuardApp", "Fatal error during app initialization: ${e.message}", e)
            e.printStackTrace()
        }
    }
}
