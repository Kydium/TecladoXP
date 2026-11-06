package com.kydium.tecladoxp

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.min

/**
 * Learns where *you* press each key. Every word you keep as typed teaches it the offset
 * between your finger and the key center (in key units). The keyboard then moves each
 * key's hit area toward where you actually tap, and the autocorrector and swipe use the
 * shifted centers too.
 */
object TouchModel {
    private var prefs: SharedPreferences? = null
    private val dx = HashMap<Char, Float>()
    private val dy = HashMap<Char, Float>()
    private val n = HashMap<Char, Int>()
    private var dirty = 0

    private const val MAX_SHIFT = 0.4f      // never move a key more than 40% of its size
    private const val MIN_SAMPLES = 8       // needs this many taps before trusting a key

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("touch", Context.MODE_PRIVATE)
        prefs = p
        val raw = p.getString("model", "") ?: ""
        for (entry in raw.split(';')) {
            val t = entry.split(',')
            if (t.size == 4 && t[0].length == 1) {
                val ch = t[0][0]
                dx[ch] = t[1].toFloatOrNull() ?: 0f
                dy[ch] = t[2].toFloatOrNull() ?: 0f
                n[ch] = t[3].toIntOrNull() ?: 0
            }
        }
    }

    /** Learned offset for a letter (key units), or null if not enough data yet. */
    fun offset(ch: Char): FloatArray? {
        val c = n[ch] ?: return null
        if (c < MIN_SAMPLES) return null
        val f = min(1f, (c - MIN_SAMPLES + 1) / 20f)   // fade in gradually
        return floatArrayOf((dx[ch] ?: 0f) * f, (dy[ch] ?: 0f) * f)
    }

    /**
     * @param word the word that was kept exactly as typed
     * @param taps finger positions (key units) for each letter, null if unknown
     * @param centers real key centers (key units), not shifted
     */
    fun learn(word: String, taps: List<FloatArray?>, centers: Map<Char, FloatArray>) {
        if (taps.size != word.length) return
        for (i in word.indices) {
            val t = taps[i] ?: continue
            val ch = word[i].lowercaseChar()
            val c = centers[ch] ?: continue
            val ox = t[0] - c[0]
            val oy = t[1] - c[1]
            if (ox * ox + oy * oy > 1.1f) continue    // probably a different key: don't learn from it
            val k = (n[ch] ?: 0) + 1
            val a = max(0.03f, 1f / k)               // running mean, then slow moving average
            dx[ch] = ((dx[ch] ?: 0f) * (1 - a) + ox * a).coerceIn(-MAX_SHIFT, MAX_SHIFT)
            dy[ch] = ((dy[ch] ?: 0f) * (1 - a) + oy * a).coerceIn(-MAX_SHIFT, MAX_SHIFT)
            n[ch] = k
        }
        if (++dirty >= 5) save()
    }

    fun save() {
        val p = prefs ?: return
        dirty = 0
        val sb = StringBuilder()
        for ((ch, c) in n) sb.append(ch).append(',').append(dx[ch] ?: 0f).append(',').append(dy[ch] ?: 0f).append(',').append(c).append(';')
        p.edit().putString("model", sb.toString()).apply()
    }

    fun reset() {
        dx.clear(); dy.clear(); n.clear()
        save()
    }

    val learnedKeys: Int get() = n.count { it.value >= MIN_SAMPLES }
}
