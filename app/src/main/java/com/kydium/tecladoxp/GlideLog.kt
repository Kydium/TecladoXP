package com.kydium.tecladoxp

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.math.hypot

/**
 * Records your swipes (in key units, measured like the standard keyboard) together with the word
 * the keyboard guessed and the word you actually wanted. It can be exported as a code to study
 * and tune the swipe recognition with real data.
 *
 * Line format: label \t guesses \t context \t source (e = trainer, u = normal use) \t x,y;x,y;…
 */
object GlideLog {
    private const val FILE = "registro_deslizado.txt"
    private const val MAX = 2000
    private const val POINTS = 24
    private var file: File? = null
    private val lines = ArrayList<String>()
    private var loaded = false
    private var centers = ""

    // swipe from normal use waiting to know which word you wanted
    private var pendingPath: String? = null
    private var pendingCands = ""
    private var pendingCtx = ""
    private var pendingDeletedAt = 0L

    fun init(ctx: Context) {
        if (file == null) file = File(ctx.filesDir, FILE)
    }

    @Synchronized
    private fun ensure() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        try {
            if (f.exists()) f.readLines().forEach { if (it.isNotBlank()) lines.add(it) }
            val c = File(f.parentFile, "registro_teclas.txt")
            if (c.exists()) centers = c.readText()
        } catch (_: Exception) {
        }
    }

    @get:Synchronized
    val count: Int get() { ensure(); return lines.size }

    @get:Synchronized
    val misses: Int get() {
        ensure()
        return lines.count { l -> val t = l.split('\t'); t.size > 1 && t[1].substringBefore(',') != t[0] }
    }

    /** Words you meant but the keyboard guessed something else (most recent first). */
    @Synchronized
    fun missedWords(max: Int = 30): List<String> {
        ensure()
        val out = LinkedHashSet<String>()
        for (l in lines.asReversed()) {
            val t = l.split('\t')
            if (t.size > 1 && t[1].substringBefore(',') != t[0] && t[0].length > 1) out.add(t[0])
            if (out.size >= max) break
        }
        return out.toList()
    }

    /** Key centers (key units) of the keyboard, saved once so the swipes can be replayed. */
    @Synchronized
    fun setCenters(c: Map<Char, FloatArray>) {
        val s = c.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}:${f(it.value[0])},${f(it.value[1])}" }
        if (s == centers) return
        centers = s
        val f = file ?: return
        try { File(f.parentFile, "registro_teclas.txt").writeText(s) } catch (_: Exception) {}
    }

    private fun f(v: Float) = String.format(Locale.US, "%.2f", v)

    private fun pathText(x: FloatArray, y: FloatArray, n: Int): String {
        if (n <= 0) return ""
        var total = 0f
        for (i in 1 until n) total += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        val sb = StringBuilder()
        if (total < 1e-4f || n == 1) {
            sb.append(f(x[0])).append(',').append(f(y[0]))
            return sb.toString()
        }
        val step = total / (POINTS - 1)
        var acc = 0f
        var out = 1
        sb.append(f(x[0])).append(',').append(f(y[0]))
        var lx = x[0]
        var ly = y[0]
        var i = 1
        while (i < n && out < POINTS - 1) {
            val d = hypot(x[i] - lx, y[i] - ly)
            if (acc + d >= step && d > 0f) {
                val t = (step - acc) / d
                lx += t * (x[i] - lx)
                ly += t * (y[i] - ly)
                sb.append(';').append(f(lx)).append(',').append(f(ly))
                out++
                acc = 0f
            } else {
                acc += d
                lx = x[i]; ly = y[i]
                i++
            }
        }
        sb.append(';').append(f(x[n - 1])).append(',').append(f(y[n - 1]))
        return sb.toString()
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    @Synchronized
    private fun add(label: String, cands: String, ctx: String, src: Char, path: String) {
        ensure()
        lines.add("${clean(label.lowercase())}\t$cands\t${clean(ctx)}\t$src\t$path")
        while (lines.size > MAX) lines.removeAt(0)
        val f = file ?: return
        try {
            if (lines.size == 1 || lines.size >= MAX) f.writeText(lines.joinToString("\n") + "\n")
            else f.appendText(lines.last() + "\n")
        } catch (_: Exception) {
        }
    }

    /** A swipe in the trainer: we already know the word you meant. */
    fun trainer(target: String, x: FloatArray, y: FloatArray, n: Int, guesses: List<String>, ctx: String?) {
        pendingPath = null
        add(target, guesses.take(3).joinToString(",") { clean(it.lowercase()) }, ctx ?: "", 'e', pathText(x, y, n))
    }

    /** A swipe while using the keyboard normally: wait to see which word you keep. */
    fun swiped(x: FloatArray, y: FloatArray, n: Int, guesses: List<String>, ctx: String?) {
        pendingPath = pathText(x, y, n)
        pendingCands = guesses.take(3).joinToString(",") { clean(it.lowercase()) }
        pendingCtx = ctx ?: ""
        pendingDeletedAt = 0L
    }

    /** You kept the swiped word (or picked another suggestion for it). */
    fun kept(word: String) {
        val p = pendingPath ?: return
        pendingPath = null
        if (word.any { it.isLetter() }) add(word, pendingCands, pendingCtx, 'u', p)
    }

    /** You deleted the swiped word: the next word you type by hand is probably the one you wanted. */
    fun deleted() {
        if (pendingPath != null) pendingDeletedAt = System.currentTimeMillis()
    }

    fun typedAfterDelete(word: String) {
        val p = pendingPath ?: return
        if (pendingDeletedAt == 0L) return
        pendingPath = null
        if (System.currentTimeMillis() - pendingDeletedAt < 20_000 && word.length > 1 && word.all { it.isLetter() })
            add(word, pendingCands, pendingCtx, 'u', p)
    }

    class Sample(val label: String, val ctx: String?, val x: FloatArray, val y: FloatArray)

    /** The saved swipes and the key centers they were made with (null if there are none). */
    @Synchronized
    fun samples(): Pair<Map<Char, FloatArray>, List<Sample>>? {
        ensure()
        val cen = HashMap<Char, FloatArray>()
        for (t in centers.split(' ')) {
            val k = t.split(':')
            if (k.size != 2 || k[0].isEmpty()) continue
            val v = k[1].split(',').mapNotNull { it.toFloatOrNull() }
            if (v.size == 2) cen[k[0][0]] = floatArrayOf(v[0], v[1])
        }
        if (cen.size < 20) return null
        val out = ArrayList<Sample>()
        for (l in lines) {
            val t = l.split('\t')
            if (t.size < 5) continue
            val pts = t[4].split(';').map { p -> p.split(',').mapNotNull { it.toFloatOrNull() } }.filter { it.size == 2 }
            if (pts.size < 2) continue
            out.add(Sample(t[0], t[2].ifEmpty { null }, FloatArray(pts.size) { pts[it][0] }, FloatArray(pts.size) { pts[it][1] }))
        }
        return Pair(cen, out)
    }

    /** Everything as one code to copy and send (gzip + base64). */
    @Synchronized
    fun export(appVersion: String, lang: String): String {
        ensure()
        val text = "TECLADOXP-DESLIZADO v1 app=$appVersion lang=$lang\nTECLAS $centers\n" + lines.joinToString("\n")
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return "TXP1:" + Base64.getEncoder().encodeToString(bos.toByteArray())
    }

    @Synchronized
    fun clear() {
        ensure()
        lines.clear()
        try { file?.delete() } catch (_: Exception) {}
    }
}

/** The trainer screen in the app tells the keyboard which word you are trying to swipe. */
object Trainer {
    @Volatile
    var target: String? = null
    /** Called on the main thread with the keyboard's guesses for a trainer swipe. */
    var onResult: ((List<String>) -> Unit)? = null
}
