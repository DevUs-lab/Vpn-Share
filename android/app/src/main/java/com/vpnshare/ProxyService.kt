package com.vpnshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class ProxyService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "proxy_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(channelId) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Proxy",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
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
            .setContentText("HTTP + SOCKS5 proxy on port $PORT")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()

        // Manifest mein foregroundServiceType="specialUse" declare hai
        startForeground(NOTIFICATION_ID, notif)

        // Screen band ho to bhi CPU chalta rahe, warna doosre device ki
        // requests der se jawab milta hain (ya doze mein ruk jati hain)
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VpnShare:proxy")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }

        // Agar port 1080 pehle se le liya gaya ho to server start nahi hoga.
        // isRunning server ki asal haalat se aata hai, is liye app ghalti se
        // "Running" nahi dikhayegi.
        if (current?.isRunning != true) {
            current = ProxyServer(PORT).also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        current?.stop()
        current = null
        try {
            wakeLock?.release()
        } catch (ignore: Exception) {
        }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PORT = 1080
        private const val NOTIFICATION_ID = 1

        @Volatile
        private var current: ProxyServer? = null

        /**
         * Server ki asal haalat (bind fail ho to false).
         * ProxyModule aur UI isi se poochte hain.
         */
        val isRunning: Boolean
            get() = current?.isRunning == true
    }
}
