package com.vpnshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import kotlin.concurrent.thread

class ProxyModule(private val ctx: ReactApplicationContext) :
    ReactContextBaseJavaModule(ctx) {

    override fun getName() = "ProxyModule"

    @ReactMethod
    fun startProxy() {
        requestNotificationPermission()
        val intent = Intent(ctx, ProxyService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    @ReactMethod
    fun stopProxy() {
        ctx.stopService(Intent(ctx, ProxyService::class.java))
    }

    @ReactMethod
    fun isRunning(promise: Promise) {
        promise.resolve(ProxyService.isRunning)
    }

    /**
     * Phone ki IPv4 addresses + hotspot (AP) interface ka IP.
     * Mobile data (ccmni/rmnet) aur VPN (tun) interfaces ko skip karta hai,
     * kyunki doosre device un IPs par proxy nahi pahuncha sakta.
     */
    @ReactMethod
    fun getNetworkInfo(promise: Promise) {
        val info = Arguments.createMap()
        val all = Arguments.createArray()
        var hotspotIp = ""
        var wlanFallback = ""
        try {
            val skip = listOf(
                "lo", "tun", "ppp", "wg", "dummy", "rmnet", "ccmni", "clat",
                "ip6tnl", "sit", "ip6gre", "v4-rmnet", "ifb",
            )
            val ranked = mutableListOf<Pair<Int, String>>()

            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (ni in interfaces.toList()) {
                    val name = ni.name.lowercase()
                    if (skip.any { name.startsWith(it) }) continue
                    try {
                        if (!ni.isUp) continue
                    } catch (e: Exception) {
                        continue
                    }

                    // Hotspot / USB tethering wale interfaces
                    val isAp = name.startsWith("ap") ||
                        name.startsWith("swlan") ||
                        name.startsWith("rndis") ||
                        name.startsWith("usb")
                    val prio = when {
                        isAp -> 0
                        name.startsWith("wlan") || name.startsWith("wifi") -> 1
                        name.startsWith("eth") -> 2
                        else -> 3
                    }

                    for (addr in ni.inetAddresses.toList()) {
                        if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("169.254.")) continue // link-local
                        ranked.add(prio to host)
                        if (isAp && hotspotIp.isEmpty()) hotspotIp = host
                        if (prio == 1 && host.startsWith("192.168.") && wlanFallback.isEmpty()) {
                            wlanFallback = host
                        }
                    }
                }
            }

            ranked.distinct()
                .sortedWith(compareBy({ it.first }, { ipRank(it.second) }))
                .forEach { all.pushString(it.second) }

            // Kuch purane phones wlan0 par AP mode chalate hain
            if (hotspotIp.isEmpty()) hotspotIp = wlanFallback
        } catch (e: Exception) {
            // ignore, khali result jayega
        }
        info.putArray("ips", all)
        info.putString("hotspotIp", hotspotIp)
        promise.resolve(info)
    }

    private fun ipRank(ip: String): Int = when {
        ip.startsWith("192.168.") -> 0
        ip.startsWith("10.") -> 1
        else -> 2
    }

    /**
     * Phone ka exit IP nikaalta hai (https pehle, phir http).
     *
     * Yeh request bhi usi socket path se guzarti hai jisse proxy ka remote
     * connection banta hai, is liye jo IP yahan aaye wahi IP doosre device
     * ko proxy ke through milni chahiye.
     */
    @ReactMethod
    fun getExitIp(promise: Promise) {
        thread(name = "ExitIp") {
            val urls = listOf(
                "https://api.ipify.org/",
                "http://api.ipify.org/",
                "https://ifconfig.me/ip",
            )
            for (u in urls) {
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(u).openConnection() as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.instanceFollowRedirects = true
                    if (conn.responseCode == 200) {
                        val text = conn.inputStream
                            .bufferedReader(Charsets.UTF_8)
                            .use { it.readText() }
                            .trim()
                        if (text.isNotEmpty()) {
                            promise.resolve(text)
                            return@thread
                        }
                    }
                } catch (e: Exception) {
                    // agla URL try karenge
                } finally {
                    conn?.disconnect()
                }
            }
            promise.reject("E_NO_IP", "Internet se IP nahi mil saka")
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val activity = ctx.currentActivity ?: return
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }
}
