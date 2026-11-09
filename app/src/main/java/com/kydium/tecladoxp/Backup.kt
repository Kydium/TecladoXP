package com.kydium.tecladoxp

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup of everything you've built with the keyboard: level and XP, achievements, settings,
 * learned words, your swipes and touch model, custom sounds. Saved as one .zip file
 * (for example in Google Drive) and restored from it on another phone or after reinstalling.
 */
object Backup {
    private val PREFS = listOf("xp", "settings", "touch")
    private const val MAGIC = "TECLADOXP-COPIA 1"
    private const val SEP = "\u0001"

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); else -> sb.append(s[i + 1]) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun backupFiles(ctx: Context): List<File> =
        ctx.filesDir.listFiles()?.filter {
            it.isFile && it.length() < 8_000_000 && !it.name.endsWith(".apk") && it.name != "ultimo_error.txt"
        } ?: emptyList()

    fun write(ctx: Context, out: OutputStream) {
        Dictionary.get(ctx.filesDir).saveNow()
        TouchModel.save()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("copia.txt"))
            zip.write("$MAGIC\n${System.currentTimeMillis()}\n".toByteArray())
            zip.closeEntry()
            for (name in PREFS) {
                val p = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
                val sb = StringBuilder()
                for ((k, v) in p.all) {
                    val line = when (v) {
                        is Boolean -> "b\t$v"
                        is Int -> "i\t$v"
                        is Long -> "l\t$v"
                        is Float -> "f\t$v"
                        is String -> "s\t${esc(v)}"
                        is Set<*> -> "S\t${esc(v.joinToString(SEP))}"
                        else -> continue
                    }
                    sb.append(esc(k)).append('\t').append(line).append('\n')
                }
                zip.putNextEntry(ZipEntry("prefs/$name.txt"))
                zip.write(sb.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            for (f in backupFiles(ctx)) {
                zip.putNextEntry(ZipEntry("files/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Replaces the current data with the backup. Returns false if it isn't a Teclado XP backup. */
    fun restore(ctx: Context, input: InputStream): Boolean {
        val prefs = HashMap<String, String>()
        val files = HashMap<String, ByteArray>()
        var ok = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                if (name.contains("..")) continue
                val data = zip.readBytes()
                when {
                    name == "copia.txt" -> ok = String(data).startsWith(MAGIC)
                    name.startsWith("prefs/") -> prefs[name.removePrefix("prefs/").removeSuffix(".txt")] = String(data, Charsets.UTF_8)
                    name.startsWith("files/") && !name.removePrefix("files/").contains('/') -> files[name.removePrefix("files/")] = data
                }
            }
        }
        if (!ok) return false
        Dictionary.get(ctx.filesDir).frozen = true
        for ((name, text) in prefs) {
            if (name !in PREFS) continue
            val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (line in text.split('\n')) {
                val t = line.split('\t', limit = 3)
                if (t.size < 3) continue
                val k = unesc(t[0])
                val v = t[2]
                when (t[1]) {
                    "b" -> ed.putBoolean(k, v == "true")
                    "i" -> v.toIntOrNull()?.let { ed.putInt(k, it) }
                    "l" -> v.toLongOrNull()?.let { ed.putLong(k, it) }
                    "f" -> v.toFloatOrNull()?.let { ed.putFloat(k, it) }
                    "s" -> ed.putString(k, unesc(v))
                    "S" -> ed.putStringSet(k, unesc(v).split(SEP).filter { it.isNotEmpty() }.toSet())
                }
            }
            ed.commit()
        }
        for (f in backupFiles(ctx)) if (f.name != "ultimo_error.txt") f.delete()
        for ((name, data) in files) File(ctx.filesDir, name).writeBytes(data)
        return true
    }
}
