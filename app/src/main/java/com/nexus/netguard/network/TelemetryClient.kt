package com.nexus.netguard.network

import com.nexus.netguard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in, anonymous: sends only node name, ping, jitter, loss and app version. No IDs, no IP logging. */
object TelemetryClient {
    suspend fun send(node: PingNode, s: LatencyStats): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL(BuildConfig.TELEMETRY_URL).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                if (BuildConfig.API_TOKEN.isNotEmpty()) conn.setRequestProperty("X-Api-Token", BuildConfig.API_TOKEN)
                val body = JSONObject()
                    .put("node", node.name)
                    .put("ping_ms", s.avgMs)
                    .put("jitter_ms", s.jitterMs)
                    .put("loss_pct", s.lossPct)
                    .put("app_version", BuildConfig.VERSION_NAME)
                    .toString()
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }
}
