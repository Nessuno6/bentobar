package com.meridian11.books.bentobar.items

/**
 * Pure rules for privileged reads (optional root or Shizuku): state, backend choice, wire encoding, and how
 * Shizuku (ADB shell or root) and `su` share or fall back for each command.
 */
object PrivRules {
    /** How Shizuku stands on this device, shown in Setup and used to decide whether it can run commands. */
    enum class ShizukuStatus {
        /** The Shizuku app is not installed on this device. */
        NOT_INSTALLED,
        /** Shizuku is installed, but its background service is not running yet. */
        NOT_RUNNING,
        /** An older Shizuku (< v11) is running; BentoBar needs v11 or newer. */
        PRE_V11,
        /** Shizuku is running, and has not granted BentoBar permission yet. */
        NEEDS_PERMISSION,
        /** Shizuku is running and BentoBar has permission to ask it. */
        GRANTED,
    }

    /** Which elevated path is ready to run commands now. */
    enum class Backend { NONE, SHIZUKU_ROOT, SHIZUKU_SHELL, ROOT }

    data class Result(val exitCode: Int, val output: String)

    fun shizukuStatus(installed: Boolean, binderAlive: Boolean, preV11: Boolean, granted: Boolean): ShizukuStatus = when {
        binderAlive && preV11 -> ShizukuStatus.PRE_V11
        binderAlive && granted -> ShizukuStatus.GRANTED
        binderAlive -> ShizukuStatus.NEEDS_PERMISSION
        installed -> ShizukuStatus.NOT_RUNNING
        else -> ShizukuStatus.NOT_INSTALLED
    }

    fun backend(shizuku: ShizukuStatus, shizukuUid: Int?, suAvailable: Boolean): Backend = when {
        shizuku == ShizukuStatus.GRANTED && shizukuUid == 0 -> Backend.SHIZUKU_ROOT
        shizuku == ShizukuStatus.GRANTED -> Backend.SHIZUKU_SHELL
        suAvailable -> Backend.ROOT
        else -> Backend.NONE
    }

    /** Whether Shizuku should be asked before `su` for a command; [rootOnly] skips non-root ADB shell. */
    fun tryShizuku(shizuku: ShizukuStatus, shizukuUid: Int?, rootOnly: Boolean): Boolean =
        shizuku == ShizukuStatus.GRANTED && (!rootOnly || shizukuUid == 0)

    /** Whether `su` may still be tried after Shizuku wasn't asked or gave nothing usable. */
    fun trySu(shizuku: ShizukuStatus, shizukuUid: Int?, suRefused: Boolean): Boolean {
        if (suRefused) return false
        // When Shizuku itself already runs as root (uid 0), running `su` would ask the same privilege twice.
        return !(shizuku == ShizukuStatus.GRANTED && shizukuUid == 0)
    }

    fun encode(exitCode: Int, output: String): String = "$exitCode\n$output"

    fun decode(wire: String?): Result? {
        if (wire == null) return null
        val sep = wire.indexOf('\n')
        val codeText = if (sep < 0) wire else wire.substring(0, sep)
        val exitCode = codeText.trim().toIntOrNull() ?: return null
        val output = if (sep < 0) "" else wire.substring(sep + 1)
        return Result(exitCode, output)
    }
}
