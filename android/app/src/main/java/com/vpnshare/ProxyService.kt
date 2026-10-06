package com.vpnshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class ProxyService : Service() {

    private var proxy: ProxyServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "proxy_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "Proxy",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }

        // Channels sirf API 26+ par hain, is liye purane Android par doosra constructor
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }
        val notif = builder
            .setContentTitle("VPN Share running")
            .setContentText("SOCKS5 proxy on port $PORT")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()

        // Manifest mein foregroundServiceType="specialUse" declare hai
        startForeground(NOTIFICATION_ID, notif)

        if (proxy?.isRunning != true) {
            proxy = ProxyServer(PORT).also { it.start() }
        }
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        proxy?.stop()
        proxy = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PORT = 1080
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set
    }
}
