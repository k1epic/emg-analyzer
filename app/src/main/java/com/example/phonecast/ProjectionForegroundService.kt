package com.k1epic.phonecast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps a correctly typed foreground service active before MediaProjection is created.
 */
class ProjectionForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // onCreate() has already called startForeground() before this broadcast.
        sendBroadcast(Intent(ACTION_READY).setPackage(packageName))
        Log.i(TAG, "Media-projection foreground service is ready")
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Phone Cast screen sharing", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Phone Cast is running")
            .setContentText("Screen sharing is active")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_READY = "com.k1epic.phonecast.PROJECTION_SERVICE_READY"
        private const val TAG = "PhoneCastService"
        private const val CHANNEL_ID = "phonecast_projection"
        private const val NOTIFICATION_ID = 1201
    }
}
