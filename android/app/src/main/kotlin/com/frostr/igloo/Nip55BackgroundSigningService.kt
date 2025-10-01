package com.frostr.igloo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Background Signing Service for Auto-Approved NIP-55 Requests
 *
 * This service runs in the :main process and enables auto-approved NIP-55 requests
 * to be processed by MainActivity without bringing the app to the foreground.
 *
 * Flow:
 * 1. Started by InvisibleNIP55Handler when permissionStatus == "allowed"
 * 2. Elevates :main process priority with foreground service
 * 3. Starts MainActivity with transparent theme and background flags
 * 4. Listens for completion broadcast from MainActivity
 * 5. Stops itself and removes notification when complete
 *
 * Key difference from Nip55KeepAliveService:
 * - KeepAliveService: Runs in :native_handler, keeps that process alive
 * - BackgroundSigningService: Runs in :main, prevents MainActivity foreground focus
 */
class Nip55BackgroundSigningService : Service() {

    companion object {
        private const val TAG = "Nip55BackgroundSigningService"
        private const val CHANNEL_ID = "nip55_background_signing"
        private const val NOTIFICATION_ID = 56

        // Intent extras
        const val EXTRA_NIP55_REQUEST = "nip55_request"
        const val EXTRA_CALLING_APP = "calling_app"
        const val EXTRA_REPLY_PENDING_INTENT = "reply_pending_intent"
        const val EXTRA_REPLY_BROADCAST_ACTION = "reply_broadcast_action"

        // Broadcast actions
        const val ACTION_SIGNING_COMPLETE = "com.frostr.igloo.BACKGROUND_SIGNING_COMPLETE"
    }

    private var completionReceiver: BroadcastReceiver? = null
    private var replyBroadcastAction: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Background signing service created in :main process (PID: ${android.os.Process.myPid()})")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Background signing service started for auto-approved request")

        if (intent == null) {
            Log.e(TAG, "Service started with null intent")
            stopSelf()
            return START_NOT_STICKY
        }

        // Start foreground immediately to elevate process priority
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Started foreground service - :main process priority elevated")

        // Extract request details
        val nip55Request = intent.getStringExtra(EXTRA_NIP55_REQUEST)
        val callingApp = intent.getStringExtra(EXTRA_CALLING_APP)
        val replyPendingIntent = intent.getParcelableExtra<PendingIntent>(EXTRA_REPLY_PENDING_INTENT)
        replyBroadcastAction = intent.getStringExtra(EXTRA_REPLY_BROADCAST_ACTION)

        if (nip55Request == null || callingApp == null || replyPendingIntent == null || replyBroadcastAction == null) {
            Log.e(TAG, "Missing required intent extras")
            stopSelf()
            return START_NOT_STICKY
        }

        // Register receiver for completion notification
        registerCompletionReceiver()

        // Start MainActivity with transparent theme for background processing
        startMainActivityInBackground(nip55Request, callingApp, replyPendingIntent, replyBroadcastAction!!)

        return START_NOT_STICKY // Service completes when request is done
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }

    override fun onDestroy() {
        Log.d(TAG, "Background signing service destroyed - cleaning up")

        // Unregister receiver
        completionReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "Unregistered completion receiver")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver was already unregistered")
            }
        }

        // Stop foreground and remove notification
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(TAG, "Stopped foreground service and removed notification")

        super.onDestroy()
    }

    /**
     * Create notification channel for the background signing service
     */
    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background NIP-55 Signing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Processes pre-approved signing requests in background"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)

            Log.d(TAG, "Created notification channel: $CHANNEL_ID")
        }
    }

    /**
     * Create low-priority notification for background signing
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Igloo")
            .setContentText("Processing secure signing request...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    /**
     * Register receiver to listen for signing completion
     */
    private fun registerCompletionReceiver() {
        val filter = IntentFilter(ACTION_SIGNING_COMPLETE)

        completionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "Received signing completion notification")

                val success = intent?.getBooleanExtra("success", false) ?: false
                val requestId = intent?.getStringExtra("request_id") ?: "unknown"

                Log.d(TAG, "Signing completed - success: $success, requestId: $requestId")

                // Stop the service now that signing is complete
                stopSelf()
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(completionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(completionReceiver, filter)
        }

        Log.d(TAG, "Registered completion receiver")
    }

    /**
     * Start MainActivity in background mode with transparent theme
     */
    private fun startMainActivityInBackground(
        nip55Request: String,
        callingApp: String,
        replyPendingIntent: PendingIntent,
        replyBroadcastAction: String
    ) {
        Log.d(TAG, "Starting MainActivity in background mode for auto-approved signing")

        val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.frostr.igloo.NIP55_SIGNING"
            putExtra("nip55_request", nip55Request)
            putExtra("calling_app", callingApp)
            putExtra("reply_pending_intent", replyPendingIntent)
            putExtra("reply_broadcast_action", replyBroadcastAction)

            // Mark as background/service-initiated request
            putExtra("background_service_request", true)

            // Use minimal flags - will move to background immediately in onCreate
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                   Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                   Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                   Intent.FLAG_ACTIVITY_NO_ANIMATION
        }

        Log.d(TAG, "Starting MainActivity with background flags: 0x${Integer.toHexString(mainActivityIntent.flags)}")

        try {
            startActivity(mainActivityIntent)
            Log.d(TAG, "Successfully started MainActivity in background mode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MainActivity", e)
            stopSelf()
        }
    }
}