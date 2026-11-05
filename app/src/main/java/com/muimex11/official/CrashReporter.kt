package com.muimex11.official

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A crash report ready to display: where it was saved + full text. */
internal data class CrashReport(val fileName: String?, val body: String)

/**
 * Last-resort crash guard. Installed in [MainApplication.onCreate], it
 * intercepts every uncaught exception, writes a full report synchronously
 * (internal storage, always writable) plus a best-effort copy to
 * Download/Muimex11 via MediaStore, then chains to the previous handler.
 *
 * On the next launch [pendingReport] picks the report up so MainActivity can
 * show it in a copyable dialog — this covers crashes during startup, where
 * no UI could ever be shown.
 */
object CrashReporter {
    private const val PENDING_FILE = "crash_pending.txt"
    private const val MAX_BODY_CHARS = 32_768

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                writeCrashLog(app, thread, error)
            } catch (_: Throwable) {
                // Never throw from a crash handler.
            } finally {
                try {
                    previous?.uncaughtException(thread, error)
                } catch (_: Throwable) {}
                // If there was no previous handler, make sure the process dies.
                try {
                    android.os.Process.killProcess(android.os.Process.myPid())
                } catch (_: Throwable) {}
                try { System.exit(10) } catch (_: Throwable) {}
            }
        }
    }

    internal fun pendingReport(ctx: Context): CrashReport? {
        return try {
            val f = java.io.File(ctx.filesDir, PENDING_FILE)
            if (!f.exists()) return@pendingReport null
            val raw = f.readText()
            if (raw.isBlank()) {
                try { f.delete() } catch (_: Exception) {}
                return@pendingReport null
            }
            val firstLine = raw.lineSequence().firstOrNull().orEmpty()
            // Header line format: ##file:<name> or ##file: where <name> may be empty.
            val name = firstLine.removePrefix("##file:").trim().ifEmpty { null }
            val body = if (firstLine.startsWith("##file:")) raw.substringAfter('\n') else raw
            CrashReport(name, body.take(MAX_BODY_CHARS))
        } catch (_: Exception) {
            null
        }
    }

    fun clearPending(ctx: Context) {
        try { java.io.File(ctx.filesDir, PENDING_FILE).delete() } catch (_: Exception) {}
    }

    private fun writeCrashLog(app: Application, thread: Thread, error: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "Muimex11_crash_$stamp.txt"
        val body = buildReport(app, thread, error)
        // 1. Internal copy — always writable, read back on next launch.
        try {
            java.io.File(app.filesDir, PENDING_FILE)
                .writeText("##file:$fileName\n$body")
        } catch (_: Exception) {}
        // 2. User-visible copy in Download/Muimex11 (best effort).
        var saved: String? = null
        try {
            if (copyToDownloads(app, fileName, body)) saved = "Download/Muimex11/$fileName"
        } catch (_: Exception) {}
        // 3. Record where (or whether) the visible copy landed.
        try {
            val marker = java.io.File(app.filesDir, PENDING_FILE)
            if (marker.exists()) {
                val cur = marker.readText()
                val footer = "\nSaved to: ${saved ?: "Download copy unavailable on this device"}"
                if (!cur.contains("\nSaved to:")) marker.writeText(cur + footer)
            }
        } catch (_: Exception) {}
    }

    private fun buildReport(app: Application, thread: Thread, error: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        try {
            pw.println("Muimex11 crash report")
            pw.println("Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            pw.println("Thread: " + thread.name)
            try {
                val pi = app.packageManager.getPackageInfo(app.packageName, 0)
                @Suppress("DEPRECATION")
                pw.println("App: ${pi.versionName} (${pi.versionCode})")
            } catch (_: Exception) {}
            pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            pw.println()
            var e: Throwable? = error
            while (e != null) {
                pw.println(e.javaClass.name + ": " + e.message)
                e.stackTrace.forEach { pw.println("\tat $it") }
                e = e.cause
                if (e != null) pw.println("Caused by:")
            }
        } catch (_: Exception) {
        } finally {
            try { pw.flush() } catch (_: Exception) {}
        }
        return sw.toString()
    }

    private fun copyToDownloads(ctx: Context, fileName: String, body: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/Muimex11")
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return false
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
                    ?: return false
                true
            } else {
                // Pre-Q needs WRITE_EXTERNAL_STORAGE which we deliberately lack.
                val dir = java.io.File(
                    android.os.Environment.getExternalStorageDirectory(), "Download/Muimex11"
                )
                if (!dir.exists() && !dir.mkdirs()) return false
                java.io.File(dir, fileName).writeText(body)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}

/** Copyable crash dialog shown on the launch after a crash. */
internal fun MainActivity.showCrashReportSheet(report: CrashReport) {
    val fileLine = report.fileName?.let { "Download/Muimex11/$it" }
        ?: getString(R.string.crash_no_file)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val body = TextView(this).apply {
        text = report.body
        textSize = 11f
        typeface = android.graphics.Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }
    list.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(320)
        )
        addView(body)
    })
    list.addView(infoCard(this, getString(R.string.crash_saved_note, fileLine)))
    Sheet(this)
        .icon(R.drawable.ic_warning)
        .title(getString(R.string.crash_title))
        .message(getString(R.string.crash_msg))
        .content(list)
        .button(getString(R.string.shell_btn_copy), SheetStyle.TONAL, dismiss = false) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("muimex11-crash", report.body))
                UiToast.success(this, getString(R.string.shell_copied), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
        .button(getString(R.string.btn_close), SheetStyle.PRIMARY) {
            CrashReporter.clearPending(this)
        }
        .show()
}
