package com.nexus.netguard.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.netguard.network.DnsResolverManager
import com.nexus.netguard.ui.theme.NexusCard
import com.nexus.netguard.ui.theme.NexusColors
import com.nexus.netguard.viewmodel.UiState

@Composable
fun DashboardScreen(
    state: UiState,
    onToggleFirewall: (Boolean) -> Unit,
    onLaunchGame: (String) -> Unit,
    onSelectNode: (Int) -> Unit,
    onProbe: () -> Unit,
    onDnsBenchmark: () -> Unit,
    onApplyDns: (DnsResolverManager.Server) -> Unit,
    onToggleDns: (Boolean) -> Unit,
    onTelemetry: (Boolean) -> Unit,
    onOpenFirewall: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text("NEXUS NETGUARD", fontSize = 24.sp, fontWeight = FontWeight.Black, color = NexusColors.Neon)
                Text("Gaming network accelerator & firewall", fontSize = 13.sp, color = NexusColors.TextDim)
            }
        }

        // ---- Ping meter ----
        item {
            NexusCard(glow = state.latency != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PingMeter(state.latency?.avgMs?.takeIf { state.latency.lossPct < 100 })
                }
                state.latency?.let {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Stat("MIN", "${it.minMs} ms")
                        Stat("JITTER", "${it.jitterMs} ms")
                        Stat("LOSS", "${it.lossPct}%")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.nodes.forEachIndexed { i, n ->
                        FilterChip(
                            selected = i == state.selectedNode,
                            onClick = { onSelectNode(i) },
                            label = { Text(n.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NexusColors.SurfaceHi,
                                selectedLabelColor = NexusColors.Neon,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Button(onClick = onProbe, enabled = !state.probing, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.probing) "Measuring…" else "Measure ping")
                }
            }
        }

        // ---- Game ----
        item {
            NexusCard {
                Text("GAME", fontSize = 12.sp, color = NexusColors.TextDim, fontWeight = FontWeight.Bold)
                if (state.games.isEmpty()) {
                    Text("PUBG Mobile not found on this device.", color = NexusColors.Text, modifier = Modifier.padding(top = 6.dp))
                } else {
                    state.games.forEach { g ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(g.label, color = NexusColors.Text, fontWeight = FontWeight.SemiBold)
                            Button(onClick = { onLaunchGame(g.pkg) }) { Text("BOOST & LAUNCH") }
                        }
                    }
                }
            }
        }

        // ---- Firewall ----
        item {
            NexusCard(glow = state.vpnActive) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (state.vpnActive) "FIREWALL ACTIVE" else "FIREWALL OFF",
                            fontWeight = FontWeight.Black,
                            color = if (state.vpnActive) NexusColors.Neon else NexusColors.TextDim,
                        )
                        Text(
                            when {
                                state.vpnActive -> "${state.blocked.size} apps blocked · ${state.dropped} packets dropped"
                                state.blocked.isEmpty() -> "Choose apps to block first"
                                else -> "${state.blocked.size} apps will be blocked"
                            },
                            fontSize = 12.sp,
                            color = NexusColors.TextDim,
                        )
                    }
                    Switch(
                        checked = state.vpnActive,
                        onCheckedChange = onToggleFirewall,
                        enabled = state.blocked.isNotEmpty() || state.vpnActive,
                        colors = SwitchDefaults.colors(checkedTrackColor = NexusColors.Neon),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenFirewall, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose apps to block (${state.blocked.size})")
                }
            }
        }

        // ---- DNS ----
        item {
            NexusCard {
                Text("DNS", fontSize = 12.sp, color = NexusColors.TextDim, fontWeight = FontWeight.Bold)
                Text(
                    "Benchmarks public resolvers from your connection. Android applies a DNS to other apps only via " +
                        "Private DNS, so Apply copies the hostname and opens network settings.",
                    fontSize = 12.sp, color = NexusColors.TextDim, modifier = Modifier.padding(vertical = 6.dp),
                )
                state.dnsResults.forEachIndexed { i, r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            r.server.name + "  " + r.server.ip,
                            modifier = Modifier.weight(1f),
                            color = if (i == 0 && r.medianMs >= 0) NexusColors.Neon else NexusColors.Text,
                        )
                        Text(if (r.medianMs >= 0) "${r.medianMs} ms" else "timeout", color = NexusColors.TextDim)
                        if (i == 0 && r.medianMs >= 0 && r.server.privateDnsHost.isNotEmpty()) {
                            Spacer(Modifier.size(8.dp))
                            Button(
                                onClick = { onApplyDns(r.server) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                colors = ButtonDefaults.buttonColors(),
                            ) { Text("Apply") }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Button(onClick = onDnsBenchmark, enabled = !state.dnsRunning, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.dnsRunning) "Testing…" else "Benchmark DNS")
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (state.dnsActive) "DNS BOOST ACTIVE" else "DNS BOOST OFF",
                            fontWeight = FontWeight.Black,
                            color = if (state.dnsActive) NexusColors.Cyan else NexusColors.TextDim,
                        )
                        Text(
                            if (state.dnsActive) "${state.dnsQueries} lookups · ${state.dnsCacheHits} from cache · via ${state.dnsUpstream.joinToString(", ")}"
                            else "System-wide fast DNS with caching. Replaces Firewall mode while on (Android allows one VPN).",
                            fontSize = 12.sp, color = NexusColors.TextDim,
                        )
                    }
                    Switch(
                        checked = state.dnsActive,
                        onCheckedChange = onToggleDns,
                        colors = SwitchDefaults.colors(checkedTrackColor = NexusColors.Cyan),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Share anonymous ping stats (optional)", fontSize = 12.sp, color = NexusColors.TextDim, modifier = Modifier.weight(1f))
                    Switch(checked = state.telemetry, onCheckedChange = onTelemetry)
                }
                Text("Profile source: ${state.configSource}", fontSize = 11.sp, color = NexusColors.TextDim, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, color = NexusColors.Text)
        Text(label, fontSize = 11.sp, color = NexusColors.TextDim)
    }
}

@Composable
private fun PingMeter(ms: Int?) {
    val target = if (ms == null) 0f else (1f - ms / 200f).coerceIn(0.05f, 1f)
    val sweep by animateFloatAsState(target * 270f, tween(900), label = "sweep")
    val color = when {
        ms == null -> NexusColors.Outline
        ms < 50 -> NexusColors.Neon
        ms < 100 -> NexusColors.Amber
        else -> NexusColors.Danger
    }
    Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 16.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = NexusColors.Outline, startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = color, startAngle = 135f, sweepAngle = sweep, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(ms?.toString() ?: "--", fontSize = 44.sp, fontWeight = FontWeight.Black, color = color)
            Text("ms PING", fontSize = 12.sp, color = NexusColors.TextDim)
        }
    }
}
