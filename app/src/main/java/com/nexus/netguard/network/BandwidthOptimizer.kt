package com.nexus.netguard.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

data class LatencyStats(val avgMs: Int, val minMs: Int, val maxMs: Int, val jitterMs: Int, val lossPct: Int)

/**
 * Measures ping, jitter and loss to a node using TCP connect round-trips, so the user can see the
 * effect of freeing bandwidth with the firewall. The actual "throttling" of background apps is done
 * by the VPN black hole in NexusVpnTunnelService; Android gives apps no way to rate-limit others.
 */
class BandwidthOptimizer {

    suspend fun probe(node: PingNode, samples: Int = 10, timeoutMs: Int = 1500): LatencyStats =
        withContext(Dispatchers.IO) {
            val addr = try {
                InetAddress.getByName(node.host)
            } catch (e: IOException) {
                return@withContext LatencyStats(0, 0, 0, 0, 100)
            }
            val rtts = ArrayList<Long>()
            var lost = 0
            repeat(samples) {
                val t0 = System.nanoTime()
                try {
                    Socket().use { it.connect(InetSocketAddress(addr, node.port), timeoutMs) }
                    rtts += (System.nanoTime() - t0) / 1_000_000L
                } catch (e: IOException) {
                    lost++
                }
                delay(100)
            }
            if (rtts.isEmpty()) return@withContext LatencyStats(0, 0, 0, 0, 100)
            val jitter = if (rtts.size < 2) 0.0 else rtts.zipWithNext { a, b -> abs(b - a).toDouble() }.average()
            LatencyStats(
                avgMs = rtts.average().toInt(),
                minMs = rtts.min().toInt(),
                maxMs = rtts.max().toInt(),
                jitterMs = jitter.toInt(),
                lossPct = lost * 100 / samples,
            )
        }
}
