package com.frostr.igloo.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.frostr.igloo.R

/**
 * Transient foreground service that protects InvisibleNIP55Handler from being killed.
 *
 * The InvisibleNIP55Handler runs in the external app's task (e.g., Amethyst) and can be
 * killed while waiting for Igloo to initialize. This service provides process priority
 * protection during the signing operation.
 *
 * Key characteristics:
 * - Lightweight: Only purpose is to keep the process alive
 * - Transient: Starts when handler becomes active, stops when handler completes
 * - Non-sticky: Does not auto-restart if killed (START_NOT_STICKY)
 * - Brief notification: "Signing request - Processing..."
 *
 * This replaces both IglooForegroundService and NIP55SigningService with a simpler,
 * focused approach.
 */
class NIP55HandlerService : Service() {

    companion object {
        private const val TAG = "NIP55HandlerService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "nip55_signing"

        /**
         * Whether the service is currently running.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Start the service to protect the handler.
         */
        fun start(context: Context) {
            if (isRunning) {
                Log.d(TAG, "Service already running, skipping start")
                return
            }

            Log.d(TAG, "Starting handler service")
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, NIP55HandlerService::class.java)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service", e)
            }
        }

        /**
         * Stop the service when handler completes.
         */
        fun stop(context: Context) {
            if (!isRunning) {
                Log.d(TAG, "Service not running, skipping stop")
                return
            }

            Log.d(TAG, "Stopping handler service")
            try {
                context.stopService(Intent(context, NIP55HandlerService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        isRunning = true

        createNotificationChannel()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            Log.d(TAG, "Foreground service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: flags=$flags, startId=$startId")
        // Don't restart if killed - the signing operation has already failed
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_title_signing))
            .setContentText(getString(R.string.service_text_wait))
            .setSmallIcon(R.drawable.ic_igloo_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NIP-55 Signing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while processing signing requests"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: $CHANNEL_ID")
        }
    }
}
