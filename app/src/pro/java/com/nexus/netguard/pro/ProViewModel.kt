package com.nexus.netguard.pro

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.netguard.network.DnsResolverManager
import com.nexus.netguard.network.NetCheck
import com.nexus.netguard.service.NexusDnsTunnelService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProState(
    val status: ShizukuStatus = ShizukuStatus.NOT_RUNNING,
    val dnsChoice: Int = 0,
    val useDns: Boolean = true,
    val applied: Boolean = false,
    val busy: Boolean = false,
    val log: List<String> = emptyList(),
)

class ProViewModel(app: Application) : AndroidViewModel(app) {

    private val sp = app.getSharedPreferences("nexus_pro", Context.MODE_PRIVATE)
    private val servers = DnsResolverManager.defaults()
    private val pkgRegex = Regex("^[A-Za-z0-9._]+$")
    private val hostRegex = Regex("^[A-Za-z0-9.-]+$")

    private val _state = MutableStateFlow(
        ProState(applied = sp.getBoolean("applied", false) || sp.getBoolean("dns_applied", false))
    )
    val state: StateFlow<ProState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                val st = ShizukuShell.status(getApplication())
                _state.update { if (it.status == st) it else it.copy(status = st) }
                delay(1500)
            }
        }
    }

    fun requestPermission() = ShizukuShell.requestPermission()
    fun selectDns(i: Int) = _state.update { it.copy(dnsChoice = i) }
    fun setUseDns(on: Boolean) = _state.update { it.copy(useDns = on) }

    private fun log(line: String) = _state.update { it.copy(log = (it.log + line).takeLast(60)) }

    private suspend fun run(cmd: String): String {
        val out = ShizukuShell.exec(cmd)
        log("$ $cmd\n${out.ifBlank { "(ok)" }}")
        return out.trim()
    }

    private fun refreshApplied() {
        val any = sp.getBoolean("applied", false) || sp.getBoolean("dns_applied", false)
        _state.update { it.copy(applied = any) }
    }

    fun apply(blocked: Set<String>, games: List<String>) {
        if (_state.value.busy || _state.value.status != ShizukuStatus.READY) return
        val host = servers[_state.value.dnsChoice].privateDnsHost
        val blockedOk = blocked.filter { pkgRegex.matches(it) }
        val gamesOk = games.filter { pkgRegex.matches(it) }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }

            if (_state.value.useDns && hostRegex.matches(host)) applyPrivateDnsSafely(host)

            gamesOk.forEach { run("cmd game mode performance $it") }
            blockedOk.forEach { p ->
                run(
                    "cmd appops set $p RUN_IN_BACKGROUND ignore; " +
                        "cmd appops set $p RUN_ANY_IN_BACKGROUND ignore; " +
                        "am set-standby-bucket $p restricted; am force-stop $p"
                )
            }
            sp.edit()
                .putBoolean("applied", gamesOk.isNotEmpty() || blockedOk.isNotEmpty())
                .putStringSet("applied_blocked", blockedOk.toSet())
                .putStringSet("applied_games", gamesOk.toSet())
                .apply()
            refreshApplied()
            _state.update { it.copy(busy = false) }
        }
    }

    /**
     * Strict Private DNS (DNS-over-TLS) silently kills internet if the network blocks port 853 or if a
     * VPN-provided DNS is active. So: pre-check, apply, verify, and roll back automatically on failure.
     */
    private suspend fun applyPrivateDnsSafely(host: String) {
        if (NexusDnsTunnelService.isRunning()) {
            log("Skipped Private DNS: DNS Boost is running. Turn it off first (the two conflict and cut internet).")
            return
        }
        if (!NetCheck.internetOk()) {
            log("Skipped Private DNS: no working internet right now, so it can't be tested safely.")
            return
        }
        if (!NetCheck.dotReachable(host)) {
            log("Skipped Private DNS: $host is not reachable on port 853 (DNS-over-TLS) on this network. " +
                "Setting it would cut your internet. Try another server, or use DNS Boost in the main tabs.")
            return
        }
        if (!sp.getBoolean("dns_applied", false)) {
            sp.edit()
                .putString("prev_mode", run("settings get global private_dns_mode"))
                .putString("prev_spec", run("settings get global private_dns_specifier"))
                .apply()
        }
        run("settings put global private_dns_specifier $host")
        run("settings put global private_dns_mode hostname")
        sp.edit().putBoolean("dns_applied", true).apply()

        delay(2500)
        if (NetCheck.internetOk(5000)) {
            log("Private DNS verified: internet works with $host.")
        } else {
            log("Internet failed after setting Private DNS. Rolling back automatically.")
            restoreDns()
        }
    }

    private suspend fun restoreDns() {
        val prevMode = sp.getString("prev_mode", null)?.takeIf { it.isNotBlank() && it != "null" } ?: "opportunistic"
        val prevSpec = sp.getString("prev_spec", null)?.takeIf { it.isNotBlank() && it != "null" }
        if (prevSpec != null && hostRegex.matches(prevSpec)) {
            run("settings put global private_dns_specifier $prevSpec")
        } else {
            run("settings delete global private_dns_specifier")
        }
        if (Regex("^[a-z_]+$").matches(prevMode)) run("settings put global private_dns_mode $prevMode")
        sp.edit().putBoolean("dns_applied", false).apply()
    }

    /** Emergency: ignores saved state and sets Private DNS back to Automatic. */
    fun resetDns() {
        if (_state.value.busy || _state.value.status != ShizukuStatus.READY) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            run("settings put global private_dns_mode opportunistic")
            run("settings delete global private_dns_specifier")
            sp.edit().putBoolean("dns_applied", false).apply()
            refreshApplied()
            _state.update { it.copy(busy = false) }
        }
    }

    fun restore() {
        if (_state.value.busy || _state.value.status != ShizukuStatus.READY) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            if (sp.getBoolean("dns_applied", false)) restoreDns()
            (sp.getStringSet("applied_games", emptySet()) ?: emptySet()).forEach { run("cmd game mode standard $it") }
            (sp.getStringSet("applied_blocked", emptySet()) ?: emptySet()).forEach { p ->
                run(
                    "cmd appops set $p RUN_IN_BACKGROUND default; " +
                        "cmd appops set $p RUN_ANY_IN_BACKGROUND default; " +
                        "am set-standby-bucket $p active"
                )
            }
            sp.edit().putBoolean("applied", false).apply()
            refreshApplied()
            _state.update { it.copy(busy = false) }
        }
    }
}
