package com.nexus.netguard.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.netguard.BuildConfig
import com.nexus.netguard.data.FirewallPrefs
import com.nexus.netguard.data.GamePackages
import com.nexus.netguard.network.BandwidthOptimizer
import com.nexus.netguard.network.DefaultConfig
import com.nexus.netguard.network.DnsResolverManager
import com.nexus.netguard.network.LatencyStats
import com.nexus.netguard.network.PingNode
import com.nexus.netguard.network.RemoteConfigClient
import com.nexus.netguard.network.TelemetryClient
import com.nexus.netguard.service.NexusDnsTunnelService
import com.nexus.netguard.service.NexusVpnTunnelService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppItem(val label: String, val pkg: String)

data class UiState(
    val apps: List<AppItem> = emptyList(),
    val games: List<AppItem> = emptyList(),
    val blocked: Set<String> = emptySet(),
    val vpnActive: Boolean = false,
    val dropped: Long = 0,
    val nodes: List<PingNode> = DefaultConfig.nodes,
    val selectedNode: Int = 0,
    val latency: LatencyStats? = null,
    val probing: Boolean = false,
    val dnsServers: List<DnsResolverManager.Server> = DefaultConfig.dns,
    val dnsResults: List<DnsResolverManager.Result> = emptyList(),
    val dnsRunning: Boolean = false,
    val configSource: String = "built-in defaults",
    val dnsActive: Boolean = false,
    val dnsQueries: Long = 0,
    val dnsCacheHits: Long = 0,
    val dnsUpstream: List<String> = emptyList(),
    val telemetry: Boolean = false,
)

class NetGuardViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = FirewallPrefs(app)
    private val optimizer = BandwidthOptimizer()
    private var hogs: List<String> = DefaultConfig.hogs

    private val _state = MutableStateFlow(
        UiState(blocked = prefs.blocked, dnsUpstream = prefs.dnsUpstream, telemetry = prefs.telemetryOptIn)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        loadApps()
        loadConfig()
        viewModelScope.launch {
            while (true) {
                val running = NexusVpnTunnelService.isRunning()
                val dropped = NexusVpnTunnelService.droppedPackets()
                val dnsOn = NexusDnsTunnelService.isRunning()
                val q = NexusDnsTunnelService.queries()
                val hits = NexusDnsTunnelService.cacheHits()
                _state.update {
                    if (it.vpnActive == running && it.dropped == dropped && it.dnsActive == dnsOn &&
                        it.dnsQueries == q && it.dnsCacheHits == hits
                    ) it
                    else it.copy(vpnActive = running, dropped = dropped, dnsActive = dnsOn, dnsQueries = q, dnsCacheHits = hits)
                }
                delay(1000)
            }
        }
    }

    private fun loadApps() = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val list = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.pkg }
                .filter { it.pkg != ctx.packageName }
                .sortedBy { it.label.lowercase() }
        }
        _state.update {
            it.copy(
                apps = list.filter { a -> a.pkg !in GamePackages.PACKAGES },
                games = list.filter { a -> a.pkg in GamePackages.PACKAGES },
            )
        }
    }

    private fun loadConfig() = viewModelScope.launch {
        val cfg = RemoteConfigClient.fetch(BuildConfig.CONFIG_URL) ?: return@launch
        if (cfg.hogs.isNotEmpty()) hogs = cfg.hogs
        _state.update {
            it.copy(
                nodes = cfg.nodes.ifEmpty { it.nodes },
                dnsServers = cfg.dns.ifEmpty { it.dnsServers },
                selectedNode = 0,
                configSource = "remote profile",
            )
        }
    }

    // ---- Firewall ----

    fun toggleBlocked(pkg: String) {
        if (pkg in GamePackages.PACKAGES) return
        val cur = _state.value.blocked
        setBlocked(if (pkg in cur) cur - pkg else cur + pkg)
    }

    fun blockHogs() {
        val installed = _state.value.apps.map { it.pkg }.toSet()
        setBlocked(_state.value.blocked + hogs.filter { it in installed })
    }

    fun clearBlocked() = setBlocked(emptySet())

    private fun setBlocked(set: Set<String>) {
        prefs.blocked = set
        _state.update { it.copy(blocked = set) }
        if (NexusVpnTunnelService.isRunning()) {
            val ctx = getApplication<Application>()
            if (set.isEmpty()) NexusVpnTunnelService.stop(ctx) else NexusVpnTunnelService.start(ctx)
        }
    }

    // ---- Latency / DNS ----

    fun selectNode(i: Int) = _state.update { it.copy(selectedNode = i, latency = null) }

    fun probe() {
        val s = _state.value
        if (s.probing) return
        val node = s.nodes.getOrNull(s.selectedNode) ?: return
        viewModelScope.launch {
            _state.update { it.copy(probing = true) }
            val stats = optimizer.probe(node)
            _state.update { it.copy(probing = false, latency = stats) }
            if (prefs.telemetryOptIn && stats.lossPct < 100) TelemetryClient.send(node, stats)
        }
    }

    fun benchmarkDns() {
        val s = _state.value
        if (s.dnsRunning) return
        viewModelScope.launch {
            _state.update { it.copy(dnsRunning = true) }
            val results = withContext(Dispatchers.IO) { DnsResolverManager.benchmark(s.dnsServers, 5, 1200) }
            val fastest = results.filter { it.medianMs >= 0 }.map { it.server.ip }
            if (fastest.isNotEmpty()) prefs.dnsUpstream = fastest.take(3)
            _state.update { it.copy(dnsRunning = false, dnsResults = results, dnsUpstream = prefs.dnsUpstream) }
            // Apply the new order immediately if DNS Boost is already running.
            if (NexusDnsTunnelService.isRunning()) NexusDnsTunnelService.start(getApplication<Application>())
        }
    }

    fun setTelemetry(on: Boolean) {
        prefs.telemetryOptIn = on
        _state.update { it.copy(telemetry = on) }
    }
}
