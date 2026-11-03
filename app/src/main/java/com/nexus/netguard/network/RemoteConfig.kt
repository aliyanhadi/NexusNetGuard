package com.nexus.netguard.network

import com.nexus.netguard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PingNode(val name: String, val host: String, val port: Int)

data class RemoteConfig(
    val nodes: List<PingNode>,
    val dns: List<DnsResolverManager.Server>,
    val hogs: List<String>,
)

/** Used when the backend is unreachable. Mirrors backend-php/lib/bootstrap.php defaults. */
object DefaultConfig {
    val nodes = listOf(
        PingNode("Cloudflare", "1.1.1.1", 443),
        PingNode("Google", "8.8.8.8", 443),
        PingNode("Quad9", "9.9.9.9", 443),
    )
    val dns: List<DnsResolverManager.Server> = DnsResolverManager.defaults()
    val hogs = listOf(
        "com.facebook.katana", "com.facebook.orca", "com.instagram.android",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.snapchat.android",
        "com.twitter.android", "com.google.android.youtube", "com.netflix.mediaclient",
        "com.spotify.music", "com.android.vending", "com.google.android.apps.photos",
    )
}

object RemoteConfigClient {
    /** Returns null on any failure; callers keep using defaults. */
    suspend fun fetch(url: String): RemoteConfig? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("Accept", "application/json")
                if (BuildConfig.API_TOKEN.isNotEmpty()) conn.setRequestProperty("X-Api-Token", BuildConfig.API_TOKEN)
                if (conn.responseCode != 200) return@withContext null
                val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })

                val nodes = root.optJSONArray("ping_nodes")?.let { a ->
                    (0 until a.length()).map { i ->
                        val o = a.getJSONObject(i)
                        PingNode(o.getString("name"), o.getString("host"), o.optInt("port", 443))
                    }
                }.orEmpty()
                val dns = root.optJSONArray("dns_servers")?.let { a ->
                    (0 until a.length()).map { i ->
                        val o = a.getJSONObject(i)
                        DnsResolverManager.Server(o.getString("name"), o.getString("ip"), o.optString("private_dns", ""))
                    }
                }.orEmpty()
                val hogs = root.optJSONArray("hog_packages")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                }.orEmpty()
                RemoteConfig(nodes, dns, hogs)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
