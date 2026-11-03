package com.nexus.netguard.pro

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.netguard.network.DnsResolverManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProState(
    val status: ShizukuStatus = ShizukuStatus.NOT_RUNNING,
    val dnsChoice: Int = 0,
    val applied: Boolean = false,
    val busy: Boolean = false,
    val log: List<String> = emptyList(),
)

class ProViewModel(app: Application) : AndroidViewModel(app) {

    private val sp = app.getSharedPreferences("nexus_pro", Context.MODE_PRIVATE)
    private val servers = DnsResolverManager.defaults()
    private val pkgRegex = Regex("^[A-Za-z0-9._]+$")
    private val hostRegex = Regex("^[A-Za-z0-9.-]+$")

    private val _state = MutableStateFlow(ProState(applied = sp.getBoolean("applied", false)))
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

    private fun log(line: String) = _state.update { it.copy(log = (it.log + line).takeLast(60)) }

    private suspend fun run(cmd: String): String {
        val out = ShizukuShell.exec(cmd)
        log("$ $cmd\n${out.ifBlank { "(ok)" }}")
        return out.trim()
    }

    /** Private DNS + Game Mode for the game, background restrictions for the apps you chose to block. */
    fun apply(blocked: Set<String>, games: List<String>) {
        if (_state.value.busy || _state.value.status != ShizukuStatus.READY) return
        val host = servers[_state.value.dnsChoice].privateDnsHost
        if (!hostRegex.matches(host)) return
        val blockedOk = blocked.filter { pkgRegex.matches(it) }
        val gamesOk = games.filter { pkgRegex.matches(it) }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            if (!sp.getBoolean("applied", false)) {
                // Remember the user's original DNS settings once, so Restore puts them back exactly.
                sp.edit()
                    .putString("prev_mode", run("settings get global private_dns_mode"))
                    .putString("prev_spec", run("settings get global private_dns_specifier"))
                    .apply()
            }
            run("settings put global private_dns_specifier $host")
            run("settings put global private_dns_mode hostname")
            gamesOk.forEach { run("cmd game mode performance $it") }
            blockedOk.forEach { p ->
                run(
                    "cmd appops set $p RUN_IN_BACKGROUND ignore; " +
                        "cmd appops set $p RUN_ANY_IN_BACKGROUND ignore; " +
                        "am set-standby-bucket $p restricted; am force-stop $p"
                )
            }
            sp.edit()
                .putBoolean("applied", true)
                .putStringSet("applied_blocked", blockedOk.toSet())
                .putStringSet("applied_games", gamesOk.toSet())
                .apply()
            _state.update { it.copy(busy = false, applied = true) }
        }
    }

    fun restore() {
        if (_state.value.busy || _state.value.status != ShizukuStatus.READY) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val prevMode = sp.getString("prev_mode", null)?.takeIf { it.isNotBlank() && it != "null" } ?: "opportunistic"
            val prevSpec = sp.getString("prev_spec", null)?.takeIf { it.isNotBlank() && it != "null" }
            if (prevSpec != null && hostRegex.matches(prevSpec)) {
                run("settings put global private_dns_specifier $prevSpec")
            } else {
                run("settings delete global private_dns_specifier")
            }
            if (Regex("^[a-z_]+$").matches(prevMode)) run("settings put global private_dns_mode $prevMode")
            (sp.getStringSet("applied_games", emptySet()) ?: emptySet()).forEach { run("cmd game mode standard $it") }
            (sp.getStringSet("applied_blocked", emptySet()) ?: emptySet()).forEach { p ->
                run(
                    "cmd appops set $p RUN_IN_BACKGROUND default; " +
                        "cmd appops set $p RUN_ANY_IN_BACKGROUND default; " +
                        "am set-standby-bucket $p active"
                )
            }
            sp.edit().putBoolean("applied", false).apply()
            _state.update { it.copy(busy = false, applied = false) }
        }
    }
}
