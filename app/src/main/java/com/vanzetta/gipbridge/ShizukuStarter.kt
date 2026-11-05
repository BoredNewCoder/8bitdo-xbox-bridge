package com.vanzetta.gipbridge

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Restarts the Shizuku server through the TV's own adbd on 127.0.0.1 — the only way to do it
 * without root. libshizuku.so kills any old server first, so this also repairs a stale one.
 * The TV asks once to allow this app's key ("Allow USB debugging?"); tick "Always allow".
 */
object ShizukuStarter {
    private const val ADB_PORT = 5555
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private val START_SCRIPT = "d=\$(dirname \$(pm path $SHIZUKU_PACKAGE | cut -d: -f2)); " +
        "for a in arm64 arm; do [ -x \$d/lib/\$a/libshizuku.so ] && exec \$d/lib/\$a/libshizuku.so; done; " +
        "echo Shizuku starter not found; exit 1"

    /** Returns the starter's output; throws on connection or authorization failure. */
    fun start(ctx: Context): String {
        val keyPair = keyPair(File(ctx.filesDir, "adbkey"))
        Dadb.create("127.0.0.1", ADB_PORT, keyPair).use { adb ->
            val result = adb.shell(START_SCRIPT)
            check(result.exitCode == 0) { "exit ${result.exitCode}: ${result.allOutput.trim()}" }
            return result.allOutput.trim()
        }
    }

    private fun keyPair(privateKey: File): AdbKeyPair {
        val publicKey = File(privateKey.path + ".pub")
        if (!privateKey.exists() || !publicKey.exists()) AdbKeyPair.generate(privateKey, publicKey)
        return AdbKeyPair.read(privateKey, publicKey)
    }
}
