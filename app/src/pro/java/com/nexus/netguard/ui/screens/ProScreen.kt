package com.nexus.netguard.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexus.netguard.network.DnsResolverManager
import com.nexus.netguard.pro.ProViewModel
import com.nexus.netguard.pro.ShizukuStatus
import com.nexus.netguard.ui.theme.NexusCard
import com.nexus.netguard.ui.theme.NexusColors
import com.nexus.netguard.viewmodel.AppItem

@Composable
fun ProScreen(blocked: Set<String>, games: List<AppItem>, vm: ProViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val dns = remember { DnsResolverManager.defaults() }
    val ready = s.status == ShizukuStatus.READY

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("PRO · SHIZUKU", fontSize = 24.sp, fontWeight = FontWeight.Black, color = NexusColors.Neon)
            Text("Not root. Uses Shizuku's ADB-level shell access.", fontSize = 13.sp, color = NexusColors.TextDim)
        }

        item {
            NexusCard(glow = ready) {
                Text(
                    when (s.status) {
                        ShizukuStatus.NOT_INSTALLED -> "Shizuku is not installed"
                        ShizukuStatus.NOT_RUNNING -> "Shizuku is installed but not running"
                        ShizukuStatus.NEEDS_PERMISSION -> "Shizuku needs your permission"
                        ShizukuStatus.CONNECTING -> "Connecting to Shizuku…"
                        ShizukuStatus.READY -> "Shizuku connected"
                    },
                    fontWeight = FontWeight.Bold,
                    color = if (ready) NexusColors.Neon else NexusColors.Amber,
                )
                when (s.status) {
                    ShizukuStatus.NOT_INSTALLED -> Button(
                        onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Get Shizuku") }
                    ShizukuStatus.NOT_RUNNING -> {
                        Text(
                            "Open Shizuku and start it (wireless debugging on Android 11+). It must be restarted after each reboot.",
                            fontSize = 12.sp, color = NexusColors.TextDim, modifier = Modifier.padding(top = 6.dp),
                        )
                        Button(
                            onClick = {
                                ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                    ?.let { ctx.startActivity(it) }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) { Text("Open Shizuku") }
                    }
                    ShizukuStatus.NEEDS_PERMISSION -> Button(
                        onClick = vm::requestPermission,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Grant access") }
                    else -> {}
                }
            }
        }

        item {
            NexusCard {
                Text("PRO BOOST", fontSize = 12.sp, color = NexusColors.TextDim, fontWeight = FontWeight.Bold)
                Text(
                    "• System-wide Private DNS, tested first and rolled back if internet fails (use it OR DNS Boost, not both)\n" +
                        "• Game Mode → performance for your PUBG build (Android 12+)\n" +
                        "• Background restrictions for the ${blocked.size} apps chosen in the Firewall tab",
                    fontSize = 12.sp, color = NexusColors.TextDim, modifier = Modifier.padding(vertical = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dns.forEachIndexed { i, d ->
                        FilterChip(selected = i == s.dnsChoice, onClick = { vm.selectDns(i) }, label = { Text(d.name) })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Set Private DNS", fontSize = 13.sp, color = NexusColors.Text, modifier = Modifier.weight(1f))
                    Switch(checked = s.useDns, onCheckedChange = vm::setUseDns)
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.apply(blocked, games.map { it.pkg }) },
                        enabled = ready && !s.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (s.busy) "Working…" else "Apply") }
                    OutlinedButton(
                        onClick = vm::restore,
                        enabled = ready && !s.busy && s.applied,
                        modifier = Modifier.weight(1f),
                    ) { Text("Restore") }
                }
                Text(
                    "Restore puts back your original Private DNS, Game Mode and app restrictions.",
                    fontSize = 11.sp, color = NexusColors.TextDim, modifier = Modifier.padding(top = 6.dp),
                )
                OutlinedButton(
                    onClick = vm::resetDns,
                    enabled = ready && !s.busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Emergency: reset DNS to Automatic") }
            }
        }

        item {
            NexusCard {
                Text("COMMAND LOG", fontSize = 12.sp, color = NexusColors.TextDim, fontWeight = FontWeight.Bold)
                if (s.log.isEmpty()) {
                    Text("Nothing run yet.", fontSize = 12.sp, color = NexusColors.TextDim, modifier = Modifier.padding(top = 4.dp))
                }
                s.log.forEach {
                    Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = NexusColors.Text, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
