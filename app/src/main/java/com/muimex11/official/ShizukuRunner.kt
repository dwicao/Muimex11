package com.muimex11.official

import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class ShellResult(val code: Int, val out: String, val err: String = "") {
    val merged: String get() = if (err.isBlank()) out else "$out\n$err".trim()
    val ok: Boolean get() = code == 0
}

/**
 * Single choke point for all Shizuku shell calls.
 * - drains stdout AND stderr (prevents pipe deadlock)
 * - enforces a timeout (prevents ANR in FPS polling / dumpsys)
 * - never throws
 */
object ShizukuRunner {
    const val DEFAULT_TIMEOUT_MS = 8_000L
    const val QUERY_TIMEOUT_MS = 5_000L

    fun exec(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult {
        var proc: Process? = null
        var executor: java.util.concurrent.ExecutorService? = null
        return try {
            proc = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val p = proc
            val outReader = p.inputStream.bufferedReader()
            val errReader = p.errorStream.bufferedReader()
            // Drain both streams on background threads to avoid blocking on full pipes.
            val outSb = StringBuilder()
            val errSb = StringBuilder()
            val t1 = Thread { try { outReader.forEachLine { outSb.append(it).append('\n') } } catch (_: Exception) {} }
            val t2 = Thread { try { errReader.forEachLine { errSb.append(it).append('\n') } } catch (_: Exception) {} }
            t1.start(); t2.start()
            // NOTE: use plain blocking waitFor() inside a Future instead of
            // waitFor(timeout, unit) + exitValue(). Shizuku's Process
            // implementation throws IllegalStateException("process hasn't
            // exited") for the timed/exitValue path on some versions.
            executor = Executors.newSingleThreadExecutor()
            val future = executor.submit<Int> { p.waitFor() }
            val code: Int = try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                try { p.destroyForcibly() } catch (_: Exception) {}
                t1.join(500); t2.join(500)
                try { outReader.close() } catch (_: Exception) {}
                try { errReader.close() } catch (_: Exception) {}
                return ShellResult(-1, outSb.toString().trim(), "timeout after ${timeoutMs}ms")
            }
            t1.join(2000); t2.join(2000)
            try { outReader.close() } catch (_: Exception) {}
            try { errReader.close() } catch (_: Exception) {}
            ShellResult(code, outSb.toString().trim(), errSb.toString().trim())
        } catch (e: Exception) {
            try { proc?.destroyForcibly() } catch (_: Exception) {}
            ShellResult(-1, "", e.message ?: "error")
        } finally {
            try { executor?.shutdownNow() } catch (_: Exception) {}
        }
    }

    fun query(cmd: String, timeoutMs: Long = QUERY_TIMEOUT_MS): String =
        exec(cmd, timeoutMs).out

    fun execOk(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean =
        exec(cmd, timeoutMs).ok
}
