package com.nexus.netguard.pro

import kotlin.system.exitProcess

/** Runs inside the Shizuku-started process with shell (ADB-level) privileges. */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String?): String {
        if (command.isNullOrBlank()) return ""
        return try {
            val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }
}
