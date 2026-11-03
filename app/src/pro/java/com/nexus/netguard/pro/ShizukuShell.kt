package com.nexus.netguard.pro

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.nexus.netguard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

enum class ShizukuStatus { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, CONNECTING, READY }

/** Thin client for the Shizuku user-service that executes shell commands. */
object ShizukuShell {
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    @Volatile private var service: IShellService? = null
    @Volatile private var binding = false

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) IShellService.Stub.asInterface(binder) else null
            binding = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
        }
    }

    fun status(ctx: Context): ShizukuStatus {
        val alive = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
        if (!alive) {
            service = null
            return if (installed(ctx)) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED
        }
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) { false }
        if (!granted) return ShizukuStatus.NEEDS_PERMISSION
        if (service == null) {
            ensureBound()
            return ShizukuStatus.CONNECTING
        }
        return ShizukuStatus.READY
    }

    private fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) { false }

    private fun ensureBound() {
        if (binding || service != null) return
        binding = true
        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Throwable) {
            binding = false
        }
    }

    fun requestPermission() {
        try { Shizuku.requestPermission(1001) } catch (e: Throwable) { /* Shizuku not running */ }
    }

    suspend fun exec(command: String): String = withContext(Dispatchers.IO) {
        try {
            service?.exec(command) ?: "Shizuku service not connected"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }
}
