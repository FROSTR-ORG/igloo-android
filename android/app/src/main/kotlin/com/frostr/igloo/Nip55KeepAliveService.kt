package com.frostr.igloo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Keep-Alive Service for NIP-55 Handler Process
 *
 * This service temporarily elevates the :native_handler process to foreground priority
 * during NIP-55 operations to prevent Android from freezing/destroying the
 * InvisibleNIP55Handler before it can receive broadcast replies.
 *
 * Based on expert analysis from ELEVATE_INTENT.md - this resolves the timing issue
 * where the InvisibleNIP55Handler process gets frozen by Android's ActivityManager
 * within ~1 second, before the PWA can process user approval and send results.
 */
class Nip55KeepAliveService : Service() {
    companion object {
        private const val TAG = "Nip55KeepAliveService"
        private const val CHANNEL_ID = "nip55_handler"
        private const val NOTIFICATION_ID = 55
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created in process: ${android.os.Process.myPid()}")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started - keeping :native_handler process alive")

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Igloo Nostr Handler")
            .setContentText("Processing secure request...")
            .setSmallIcon(R.mipmap.ic_launcher)  // Use existing app icon
            .setPriority(NotificationCompat.PRIORITY_LOW)  // Non-intrusive
            .setOngoing(true)  // Prevents swipe-dismiss during wait
            .setAutoCancel(false)  // Keep notification until explicitly removed
            .build()

        startForeground(NOTIFICATION_ID, notification)

        Log.d(TAG, "Foreground service started with notification - process priority elevated")

        // Service now keeps process alive; no heavy work here
        return START_STICKY  // Restart if killed (rare with foreground)
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Not bound - started service only
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed - stopping foreground and cleaning up notification")
        stopForeground(STOP_FOREGROUND_REMOVE)  // Clean up notification
        super.onDestroy()
    }

    /**
     * Create notification channel for Android 8.0+ (API level 26+)
     * Required for foreground service notifications
     */
    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NIP-55 Handler",
                NotificationManager.IMPORTANCE_LOW  // Low importance = non-intrusive
            ).apply {
                description = "Keeps handler alive for secure operations"
                setShowBadge(false)  // Don't show badge on launcher icon
                enableVibration(false)  // Silent operation
                enableLights(false)  // No LED notification
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)

            Log.d(TAG, "Notification channel created: $CHANNEL_ID")
        }
    }
}