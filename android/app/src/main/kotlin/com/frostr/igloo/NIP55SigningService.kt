package com.frostr.igloo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service to keep the process alive while waiting for NIP-55 signing results.
 *
 * This is necessary because InvisibleNIP55Handler needs to stay alive to receive callbacks
 * from MainActivity via the PendingNIP55ResultRegistry after launching MainActivity and
 * calling moveTaskToBack().
 */
class NIP55SigningService : Service() {

    companion object {
        private const val TAG = "NIP55SigningService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "nip55_signing_channel"

        fun start(context: Context) {
            val intent = Intent(context, NIP55SigningService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "Service start requested")
        }

        fun stop(context: Context) {
            val intent = Intent(context, NIP55SigningService::class.java)
            context.stopService(intent)
            Log.d(TAG, "Service stop requested")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        // Create notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Signing Nostr event")
            .setContentText("Please wait...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        // Start foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NIP-55 Signing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps app alive during event signing"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }
}
