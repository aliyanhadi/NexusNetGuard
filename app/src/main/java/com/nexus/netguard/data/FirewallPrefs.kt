package com.nexus.netguard.data

import android.content.Context
import com.nexus.netguard.service.NexusDnsTunnelService
import com.nexus.netguard.service.NexusVpnTunnelService

/** Persists the user's blocked-app list. The VPN service reads the same SharedPreferences file. */
class FirewallPrefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences(NexusVpnTunnelService.PREFS, Context.MODE_PRIVATE)

    var blocked: Set<String>
        get() = sp.getStringSet(NexusVpnTunnelService.KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()
        set(value) {
            sp.edit().putStringSet(NexusVpnTunnelService.KEY_BLOCKED, HashSet(value)).apply()
        }

    /** Ordered upstream resolvers used by DNS Boost (fastest first). */
    var dnsUpstream: List<String>
        get() = (sp.getString(NexusDnsTunnelService.KEY_UPSTREAM, NexusDnsTunnelService.DEFAULT_UPSTREAM) ?: "")
            .split(",").filter { it.isNotBlank() }
        set(value) {
            sp.edit().putString(NexusDnsTunnelService.KEY_UPSTREAM, value.joinToString(",")).apply()
        }

    var telemetryOptIn: Boolean
        get() = sp.getBoolean("telemetry_opt_in", false)
        set(value) {
            sp.edit().putBoolean("telemetry_opt_in", value).apply()
        }
}
