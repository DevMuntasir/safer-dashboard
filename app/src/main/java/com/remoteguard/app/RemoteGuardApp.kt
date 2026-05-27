package com.remoteguard.app

import android.app.Application
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

class RemoteGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { FirebaseDatabase.getInstance().setPersistenceEnabled(true) }
        Log.d("RemoteGuardApp", "Application onCreate - Initializing Firebase and Cloudinary")
        FirebaseHelper.initialize(this)
    }
}
