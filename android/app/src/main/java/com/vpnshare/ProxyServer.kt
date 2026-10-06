package com.vpnshare

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Ek chhota proxy server, do protocol support karti hai:
 *
 *  1. SOCKS5  (port 1080) - Firefox, ProxyDroid jaise clients
 *  2. HTTP CONNECT / absolute-URI proxy - Android Wi-Fi > Advanced > Proxy > Manual
 *
 * Client sirf host/port bhejta hai, aur hum phone se us host ko connect
 * karte hain. Yeh outbound traffic phone ke app se nikalta hai, is liye
 * VPN on ho to traffic tun0 se guzarta hai.
 */
class ProxyServer(private val port: Int) {

    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null

    val isRunning: Boolean
        get() = running

    fun start() {
        if (running) return
        running = true
        thread(name = "ProxyServer") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                // 0.0.0.0 -> hotspot/USB tethering se aane wale clients bhi connect kar saken
                ss.bind(InetSocketAddress(port))
                server = ss
                while (running) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    thread { handle(client) }
                }
            } catch (e: Exception) {
                // socket bind/accept fail ho gaya
            } finally {
                running = false
                try {
                    server?.close()
                } catch (e: Exception) {
                }
            }
        }
    }

    fun stop() {
        running = false
        val ss = server
        server = null
        try {
            ss?.close()
        } catch (e: Exception) {
        }
    }

    private fun handle(client: Socket) {
        var remote: Socket? = null
        try {
            client.keepAlive = true
            client.soTimeout = SOCKET_TIMEOUT_MS
            val inp = DataInputStream(client.getInputStream())
            val out = client.getOutputStream()

            // Pehla byte hi protocol batata hai: 0x05 = SOCKS5, warna HTTP proxy
            val first = inp.readUnsignedByte()
            remote = if (first == 5) {
                socks5Connect(client, inp, out)
            } else {
                httpConnect(client, inp, out, first)
            }
            val r = remote ?: return
            r.soTimeout = SOCKET_TIMEOUT_MS
            r.keepAlive = true

            thread { pipe(client, r) }
            pipe(r, client)
        } catch (e: Exception) {
            try {
                remote?.close()
            } catch (ignore: Exception) {
            }
            try {
                client.close()
            } catch (ignore: Exception) {
            }
        }
    }

    /** SOCKS5 handshake. Connection ban jaye to warna socket, warna null (client already band). */
    private fun socks5Connect(client: Socket, inp: DataInputStream, out: OutputStream): Socket? {
        // 1) Greeting: sirf "no authentication" (method 0) accept karte hain
        val nMethods = inp.readUnsignedByte()
        if (nMethods > 0) {
            inp.readFully(ByteArray(nMethods))
        }
        out.write(byteArrayOf(5, 0))

        // 2) Request
        inp.readUnsignedByte() // version
        val cmd = inp.readUnsignedByte()
        inp.readUnsignedByte() // reserved
        val host = when (inp.readUnsignedByte()) {
            1 -> ByteArray(4).also { inp.readFully(it) }
                .joinToString(".") { (it.toInt() and 0xff).toString() }

            3 -> String(
                ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) },
                Charsets.ISO_8859_1,
            )

            4 -> java.net.InetAddress.getByAddress(
                ByteArray(16).also { inp.readFully(it) },
            ).hostAddress ?: ""

            else -> {
                client.close()
                return null
            }
        }
        val port = inp.readUnsignedShort()

        if (cmd != 1) { // sirf CONNECT support hai
            out.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
            client.close()
            return null
        }

        val remote = Socket()
        remote.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        return remote
    }

    /**
     * HTTP proxy: CONNECT (HTTPS ke liye) aur plain HTTP (absolute-URI) dono.
     * Android Wi-Fi > Proxy > Manual isi tarah connect karta hai.
     */
    private fun httpConnect(
        client: Socket,
        inp: DataInputStream,
        out: OutputStream,
        firstByte: Int,
    ): Socket? {
        val raw = readHttpHeaders(inp, firstByte)
        val lines = raw.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty()
        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            client.close()
            return null
        }
        val method = parts[0].uppercase()

        val headers = lines.drop(1).filter {
            it.isNotEmpty() &&
                !it.startsWith("Proxy-Connection:", true) &&
                !it.startsWith("Proxy-Authorization:", true)
        }

        val remote = Socket()

        if (method == "CONNECT") {
            // Tunnel ban gaya, ab sirf raw bytes aage-jate hain
            val target = parts[1]
            val host = target.substringBefore(':')
            val port = target.substringAfter(':', "443").toIntOrNull() ?: 443
            remote.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            return remote
        }

        // GET/POST... absolute-URI (http://host/path) ya origin-form (path + Host header)
        var host = ""
        var port = 80
        var path = parts[1]
        val uri = parts[1]
        if (uri.startsWith("http://", true) || uri.startsWith("https://", true)) {
            val isHttps = uri.startsWith("https://", true)
            val rest = uri.substring(uri.indexOf("://") + 3)
            val slash = rest.indexOf('/')
            val authority = (if (slash >= 0) rest.substring(0, slash) else rest)
                .substringAfterLast('@')
            path = if (slash >= 0) rest.substring(slash) else "/"
            host = authority.substringBefore(':')
            port = authority.substringAfter(':', "").toIntOrNull()
                ?: if (isHttps) 443 else 80
        } else {
            val hostHeader = headers.firstOrNull { it.startsWith("Host:", true) }.orEmpty()
            val value = hostHeader.substringAfter(':').trim()
            host = value.substringBefore(':').trim()
            port = value.substringAfter(':', "").toIntOrNull() ?: 80
        }

        if (host.isEmpty()) {
            client.close()
            return null
        }

        remote.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

        // Origin-form request line bana kar bhejte hain (proxy wali line nahi)
        val firstLine = "${parts[0]} $path ${parts[2]}"
        val outbound = firstLine + "\r\n" + headers.joinToString("\r\n") + "\r\n\r\n"
        remote.getOutputStream().write(outbound.toByteArray(Charsets.ISO_8859_1))
        remote.getOutputStream().flush()
        return remote
    }

    /** \r\n\r\n tak headers padhta hai (pehla byte already aa chuka hai). */
    private fun readHttpHeaders(inp: DataInputStream, firstByte: Int): String {
        val sb = StringBuilder(512)
        sb.append(firstByte.toChar())
        while (sb.length < MAX_HEADER_BYTES) {
            sb.append(inp.readUnsignedByte().toChar())
            if (sb.length >= 4 && sb.endsWith("\r\n\r\n")) {
                return sb.toString()
            }
        }
        throw IOException("HTTP headers too large")
    }

    private fun pipe(from: Socket, to: Socket) {
        try {
            val buf = ByteArray(8192)
            val i = from.getInputStream()
            val o = to.getOutputStream()
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
            }
        } catch (ignore: Exception) {
        } finally {
            try {
                from.close()
            } catch (ignore: Exception) {
            }
            try {
                to.close()
            } catch (ignore: Exception) {
            }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKET_TIMEOUT_MS = 600_000 // 10 min: ruke hue connections khud band hon
        private const val MAX_HEADER_BYTES = 65_536
    }
}
