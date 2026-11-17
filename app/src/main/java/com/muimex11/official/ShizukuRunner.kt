package com.muimex11.official

import rikka.shizuku.Shizuku
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

    // Shared pool — creating a new executor per exec() churns threads
    // during 1s FPS polling and risks thread leaks on timeout paths.
    private val waitPool: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "shizuku-wait").apply { isDaemon = true }
        }
    }

    /** Cheap binder + permission gate so callers can skip work early. */
    fun isReady(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    /**
     * Runs several read-only queries in **one** shell process and returns the
     * tagged output (`key -> text`).
     *
     * The old helper joined commands with `;`, so only the *last* exit code
     * survived and a caller could not tell which command failed. Markers keep
     * every answer addressable, and a status refresh that used to fork a
     * process per `settings get` now forks one.
     */
    fun runTagged(
        reqs: List<Pair<String, String>>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Map<String, String> {
        if (reqs.isEmpty()) return emptyMap()
        val res = exec(ShellBatcher.build(reqs), timeoutMs)
        return ShellBatcher.parse(res.out)
    }

    fun exec(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult {
        var proc: Process? = null
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
            val future = waitPool.submit<Int> { p.waitFor() }
            val code: Int = try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                killQuietly(p)
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
            proc?.let { killQuietly(it) }
            ShellResult(-1, "", e.message ?: "error")
        }
        // Shared waitPool is never shut down here by design.
    }

    /**
     * `Process.destroyForcibly()` only exists from API 26; on Android 7.x the
     * call throws NoSuchMethodError, which is an Error and would slip through
     * an `catch (Exception)` and kill the process. Fall back to destroy().
     */
    private fun killQuietly(proc: Process) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                proc.destroyForcibly()
            } else {
                proc.destroy()
            }
        } catch (_: Exception) {
            // Best-effort: the caller only needs the process to stop.
        }
    }

    fun query(cmd: String, timeoutMs: Long = QUERY_TIMEOUT_MS): String =
        exec(cmd, timeoutMs).out

    fun execOk(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean =
        exec(cmd, timeoutMs).ok
}
