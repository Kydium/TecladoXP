package com.kydium.tecladoxp

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last errors in a small file so they can be seen and copied from the app.
 * Errors caught with [safe] are only written down: the keyboard keeps working.
 */
object CrashLog {
    private const val FILE = "ultimo_error.txt"
    private var file: File? = null
    private var installed = false
    private var version = "?"
    private var lastMsg = ""
    private var lastTime = 0L

    fun init(ctx: Context) {
        if (file == null) file = File(ctx.filesDir, FILE)
        if (version == "?") version = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            "${pi.versionName} ($code)"
        } catch (_: Throwable) {
            "?"
        }
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try { write(e, "El teclado se cerró") } catch (_: Throwable) {}
            previous?.uncaughtException(thread, e)
        }
    }

    /** Writes down an error that was caught. The same error is written at most every 10 s. */
    fun log(e: Throwable, where: String = "") {
        val msg = e.toString() + where
        val now = System.currentTimeMillis()
        if (msg == lastMsg && now - lastTime < 10_000) return
        lastMsg = msg
        lastTime = now
        try { write(e, if (where.isEmpty()) "Error evitado" else "Error evitado ($where)") } catch (_: Throwable) {}
    }

    @Synchronized
    private fun write(e: Throwable, kind: String) {
        val f = file ?: return
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = "$kind · $date\n" +
            "Versión $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            sw.toString().take(6000)
        val old = if (f.exists()) f.readText() else ""
        val all = if (old.isEmpty()) entry else entry + "\n\n──────────\n\n" + old
        f.writeText(all.take(20000))
    }

    fun read(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE)
        return if (f.exists()) f.readText().ifBlank { null } else null
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
        lastMsg = ""
    }

    /** Runs [block]; if it fails the error is written down instead of closing the keyboard. */
    inline fun safe(where: String = "", block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log(e, where)
        }
    }
}
