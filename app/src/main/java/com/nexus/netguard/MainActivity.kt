package com.nexus.netguard

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexus.netguard.network.DnsResolverManager
import com.nexus.netguard.service.NexusDnsTunnelService
import com.nexus.netguard.service.NexusVpnTunnelService
import com.nexus.netguard.ui.screens.DashboardScreen
import com.nexus.netguard.ui.screens.FirewallConfigScreen
import com.nexus.netguard.ui.screens.ProScreen
import com.nexus.netguard.ui.theme.NexusColors
import com.nexus.netguard.ui.theme.NexusTheme
import com.nexus.netguard.viewmodel.NetGuardViewModel

class MainActivity : ComponentActivity() {

    private val vm: NetGuardViewModel by viewModels()

    private var pendingAction: (() -> Unit)? = null

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) pendingAction?.invoke()
        pendingAction = null
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            NexusTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableIntStateOf(0) }

                Scaffold(
                    containerColor = NexusColors.Bg,
                    bottomBar = {
                        NavigationBar(containerColor = NexusColors.Surface) {
                            val colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = NexusColors.Neon,
                                selectedTextColor = NexusColors.Neon,
                                indicatorColor = NexusColors.SurfaceHi,
                            )
                            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("⚡") }, label = { Text("Hub") }, colors = colors)
                            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("🛡") }, label = { Text("Firewall") }, colors = colors)
                            if (ProFeatures.ENABLED) {
                                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("🔧") }, label = { Text("Pro") }, colors = colors)
                            }
                        }
                    },
                ) { padding ->
                    val mod = Modifier.padding(padding)
                    androidx.compose.foundation.layout.Box(mod) {
                        if (tab == 0) {
                            DashboardScreen(
                                state = state,
                                onToggleFirewall = ::setFirewall,
                                onLaunchGame = ::launchGame,
                                onSelectNode = vm::selectNode,
                                onProbe = vm::probe,
                                onDnsBenchmark = vm::benchmarkDns,
                                onApplyDns = ::applyDns,
                                onToggleDns = ::setDnsBoost,
                                onTelemetry = vm::setTelemetry,
                                onOpenFirewall = { tab = 1 },
                            )
                        } else if (tab == 2) {
                            ProScreen(blocked = state.blocked, games = state.games)
                        } else {
                            FirewallConfigScreen(
                                state = state,
                                onToggle = vm::toggleBlocked,
                                onBlockHogs = vm::blockHogs,
                                onClear = vm::clearBlocked,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun setFirewall(on: Boolean) {
        if (!on) {
            NexusVpnTunnelService.stop(this)
            return
        }
        if (vm.state.value.blocked.isEmpty()) return
        withVpnConsent { NexusVpnTunnelService.start(this) }
    }

    private fun setDnsBoost(on: Boolean) {
        if (!on) {
            NexusDnsTunnelService.stop(this)
            return
        }
        withVpnConsent { NexusDnsTunnelService.start(this) }
    }

    /** Runs [action] now if VPN consent is already granted, otherwise after the user accepts the dialog. */
    private fun withVpnConsent(action: () -> Unit) {
        val consent = VpnService.prepare(this)
        if (consent == null) {
            action()
        } else {
            pendingAction = action
            vpnPermission.launch(consent)
        }
    }

    /** One tap: turn the firewall on (if apps are selected and no VPN mode is running), then open the game. */
    private fun launchGame(pkg: String) {
        val s = vm.state.value
        if (s.blocked.isNotEmpty() && !s.vpnActive && !s.dnsActive) {
            withVpnConsent {
                NexusVpnTunnelService.start(this)
                openGame(pkg)
            }
        } else {
            openGame(pkg)
        }
    }

    private fun openGame(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) startActivity(intent)
        else Toast.makeText(this, "Game not launchable", Toast.LENGTH_SHORT).show()
    }

    private fun applyDns(server: DnsResolverManager.Server) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Private DNS", server.privateDnsHost))
        Toast.makeText(
            this,
            "Copied ${server.privateDnsHost}. Set it under Private DNS → hostname.",
            Toast.LENGTH_LONG,
        ).show()
        startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }
}
