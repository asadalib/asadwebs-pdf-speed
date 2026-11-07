package com.asadwebs.pdfspeed.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Breadcrumb trail written synchronously to disk at every important step.
 *  Unlike the crash handler (which only sees Java exceptions), this survives
 *  system kills: after any unexplained close, the last lines show exactly
 *  how far the app got. Read/share it from the Me tab → Session log. */
object Trace {
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "trace.log")
        log("=== APP START ===")
    }

    @Synchronized
    fun log(step: String) {
        val f = file ?: return
        try {
            if (f.length() > 300_000) f.writeText("")
            f.appendText(fmt.format(Date()) + "  " + step + "\n")
        } catch (e: Exception) { /* tracing must never break the app */ }
    }

    fun read(): String {
        val f = file ?: return "(no log yet)"
        return try {
            if (f.exists()) f.readText().ifBlank { "(log is empty)" } else "(no log yet)"
        } catch (e: Exception) { "(could not read log)" }
    }

    fun tail(lines: Int): String {
        val all = read().split("\n")
        return all.takeLast(lines).joinToString("\n")
    }

    fun clear() {
        try { file?.writeText("") } catch (e: Exception) { }
        log("log cleared")
    }
}
