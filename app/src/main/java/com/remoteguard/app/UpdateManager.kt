package com.remoteguard.app

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.google.firebase.database.FirebaseDatabase
import java.io.File

object UpdateManager {
    private const val TAG = "UpdateManager"
    private var downloadId: Long = -1L
    private var downloadReceiver: BroadcastReceiver? = null

    fun checkForUpdates(context: Context) {
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current version: ${e.message}")
            return
        }

        FirebaseDatabase.getInstance().reference
            .child("app_config")
            .get()
            .addOnSuccessListener { snapshot ->
                try {
                    val latestVersion = snapshot.child("latest_version").value.toString().toInt()
                    val downloadUrl = snapshot.child("download_url").value.toString()
                    val forceUpdate = snapshot.child("force_update").value as? Boolean ?: false

                    Log.d(TAG, "Current: $currentVersion, Latest: $latestVersion, Force: $forceUpdate")

                    if (latestVersion > currentVersion) {
                        showUpdateDialog(context, downloadUrl, forceUpdate)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing version info: ${e.message}")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to check for updates: ${e.message}")
            }
    }

    private fun showUpdateDialog(context: Context, downloadUrl: String, forceUpdate: Boolean) {
        try {
            val dialogBuilder = AlertDialog.Builder(context)
                .setTitle("Update Available")
                .setMessage("A new version of RemoteGuard is available. Please update to continue.")
                .setPositiveButton("Update") { _, _ ->
                    downloadAPK(context, downloadUrl)
                }

            if (!forceUpdate) {
                dialogBuilder.setNegativeButton("Later") { dialog, _ -> dialog.dismiss() }
            }

            dialogBuilder.setCancelable(!forceUpdate)
            dialogBuilder.show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show update dialog: ${e.message}")
        }
    }

    private fun downloadAPK(context: Context, url: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setDestinationInExternalFilesDir(context, null, "RemoteGuard-update.apk")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setTitle("Downloading RemoteGuard Update")
                .setDescription("Updating RemoteGuard to latest version")

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = downloadManager.enqueue(request)

            Log.d(TAG, "Download started with ID: $downloadId")

            registerDownloadReceiver(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start download: ${e.message}")
        }
    }

    private fun registerDownloadReceiver(context: Context) {
        if (downloadReceiver != null) return

        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == downloadId) {
                    Log.d(TAG, "Download complete")
                    installAPK(context ?: return)
                    context.unregisterReceiver(this)
                    downloadReceiver = null
                }
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(downloadReceiver, filter)
        }
    }

    private fun installAPK(context: Context) {
        try {
            val apkFile = File(context.getExternalFilesDir(null), "RemoteGuard-update.apk")
            if (!apkFile.exists()) {
                Log.e(TAG, "APK file not found: ${apkFile.absolutePath}")
                return
            }

            val intent = Intent(Intent.ACTION_VIEW)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            intent.apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            Log.d(TAG, "Starting installation from: ${apkFile.absolutePath}")
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK: ${e.message}")
        }
    }
}
