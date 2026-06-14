package com.remoteguard.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class LocationManager(private val context: Context) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun getCurrentLocation(onSuccess: (Location) -> Unit = {}) {
        if (!checkLocationPermission()) {
            Log.e("LocationManager", "Location permission not granted")
            return
        }

        Log.d("LocationManager", "Getting current location")
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                location?.let {
                    Log.d("LocationManager", "Location obtained: ${it.latitude}, ${it.longitude}")
                    onSuccess(it)
                    FirebaseHelper.updateLocation(context, it.latitude, it.longitude, it.accuracy)
                }
            }
            .addOnFailureListener { exception ->
                Log.e("LocationManager", "Failed to get location: ${exception.message}", exception)
            }
    }
}
