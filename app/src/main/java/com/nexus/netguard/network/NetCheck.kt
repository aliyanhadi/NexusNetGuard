package com.nexus.netguard.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Safety checks so we never set a Private DNS that would cut the user's internet. */
object NetCheck {

    /** True if [host] accepts a TLS handshake on port 853 (DNS-over-TLS) from this network. */
    suspend fun dotReachable(host: String, timeoutMs: Int = 3500): Boolean = withContext(Dispatchers.IO) {
        try {
            val addr = InetAddress.getByName(host)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            Socket().use { raw ->
                raw.connect(InetSocketAddress(addr, 853), timeoutMs)
                raw.soTimeout = timeoutMs
                (factory.createSocket(raw, host, 853, true) as SSLSocket).use { it.startHandshake() }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** True if the standard connectivity-check URL answers with HTTP 204. */
    suspend fun internetOk(timeoutMs: Int = 4000): Boolean = withContext(Dispatchers.IO) {
        try {
            val c = URL("https://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            val code = c.responseCode
            c.disconnect()
            code == 204
        } catch (e: Exception) {
            false
        }
    }
}
