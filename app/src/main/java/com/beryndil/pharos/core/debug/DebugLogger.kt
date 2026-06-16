package com.beryndil.pharos.core.debug

import android.content.Context
import android.os.Build
import android.util.Log
import com.beryndil.pharos.BuildConfig
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Persistent, file-backed diagnostic log for support sharing.
 *
 * Writes to [filesDir]/pharos_debug.log. Rotates to pharos_debug.log.old at 200 KB.
 * The log file is exposed via FileProvider so the user can share it from Settings → Support.
 *
 * Logging policy: structural events only — DB versions, query result counts, HTTP status codes,
 * exception class names. Never log medication names, dose values, or other user health data.
 * Drug search query strings ARE logged (drug name is not personal health data).
 */
object DebugLogger {

    private const val LOG_FILE = "pharos_debug.log"
    private const val OLD_LOG_FILE = "pharos_debug.log.old"
    private const val MAX_SIZE_BYTES = 200 * 1024

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE)
        rotate()
        log("Session", "=== Pharos v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ===")
        log("Session", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) — ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun log(tag: String, message: String) {
        val line = "[${LocalDateTime.now().format(fmt)}] $tag: $message\n"
        synchronized(this) {
            try {
                logFile?.appendText(line)
            } catch (_: Exception) {}
        }
        Log.d("PharosDebug", "$tag: $message")
    }

    fun logError(tag: String, message: String, e: Throwable? = null) {
        val detail = if (e != null) " — ${e.javaClass.simpleName}: ${e.message?.take(100)}" else ""
        log(tag, "ERROR $message$detail")
        if (e != null) {
            val frames = e.stackTrace.take(6)
                .joinToString("; ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            log(tag, "  stack: $frames")
        }
    }

    fun getLogFile(context: Context): File = File(context.filesDir, LOG_FILE)
    fun getOldLogFile(context: Context): File = File(context.filesDir, OLD_LOG_FILE)

    private fun rotate() {
        val f = logFile ?: return
        if (f.exists() && f.length() > MAX_SIZE_BYTES) {
            val old = File(f.parent, OLD_LOG_FILE)
            old.delete()
            f.renameTo(old)
        }
    }
}
