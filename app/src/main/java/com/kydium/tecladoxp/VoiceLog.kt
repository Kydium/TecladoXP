package com.kydium.tecladoxp

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the dictation did, second by second (to see where it cuts or restarts). Last ~400 lines. */
object VoiceLog {
    private const val FILE = "registro_dictado.txt"
    private var file: File? = null
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, FILE)
        try { file?.takeIf { it.exists() }?.readLines()?.let { lines.addAll(it.takeLast(400)) } } catch (_: Exception) {}
    }

    @Synchronized
    fun add(event: String) {
        lines.add(fmt.format(Date()) + "  " + event.replace('\n', ' '))
        while (lines.size > 400) lines.removeAt(0)
        try { file?.writeText(lines.joinToString("\n")) } catch (_: Exception) {}
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        try { file?.delete() } catch (_: Exception) {}
    }
}
