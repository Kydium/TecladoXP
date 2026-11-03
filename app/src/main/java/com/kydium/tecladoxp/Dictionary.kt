package com.kydium.tecladoxp

import java.io.File
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

class Suggestions(
    /** 3 slots: [left, center, right]. Empty string = nothing. */
    val slots: List<String>,
    /** Word that will replace the typed one when space is pressed (null = none). */
    val autoCorrect: String?,
    val typedValid: Boolean
)

/**
 * Spanish dictionary with:
 *  - autocorrect (weighted Damerau-Levenshtein with keyboard proximity, accent-insensitive)
 *  - completions (prefix search)
 *  - glide/swipe decoding (shape matching against ideal key paths)
 *  - learning: user words + bigrams (next word prediction)
 */
class Dictionary(private val userDir: File?) {

    @Volatile var loaded = false
        private set

    private var words: Array<String> = emptyArray()
    private var norms: Array<String> = emptyArray()
    private var gpaths: Array<String> = emptyArray()
    private var freqs: IntArray = IntArray(0)
    private var exact: HashMap<String, Int> = HashMap()
    private var sortedIdx: IntArray = IntArray(0)
    private var byLen: HashMap<Int, IntArray> = HashMap()
    private var glideBuckets: HashMap<Int, IntArray> = HashMap()

    private val userWords = HashMap<String, Int>()
    private val bigrams = HashMap<String, HashMap<String, Int>>()
    @Volatile private var dirty = false

    // ---------------------------------------------------------------- loading

    @Synchronized
    fun load(input: InputStream) {
        val w = ArrayList<String>(52000)
        val f = ArrayList<Int>(52000)
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    w.add(line.substring(0, tab))
                    f.add(line.substring(tab + 1).trim().toIntOrNull() ?: 300)
                }
            }
        }
        val n = w.size
        val wa = w.toTypedArray()
        val na = Array(n) { norm(wa[it]) }
        val ga = Array(n) { dedupe(glideNorm(wa[it])) }
        val fa = IntArray(n) { f[it] }
        val ex = HashMap<String, Int>(n * 2)
        for (i in 0 until n) if (!ex.containsKey(wa[i])) ex[wa[i]] = i
        // "cancion" appears in web text, but "canción" is far more common: treat the
        // accent-less spelling as a typo so it gets corrected.
        val bestByNorm = HashMap<String, Int>(n * 2)
        for (i in 0 until n) {
            val b = bestByNorm[na[i]]
            if (b == null || fa[i] > fa[b]) bestByNorm[na[i]] = i
        }
        for (i in 0 until n) {
            if (wa[i] != na[i]) continue
            val b = bestByNorm[na[i]] ?: continue
            if (b != i && fa[b] >= fa[i] + 70) ex.remove(wa[i])
        }
        val sorted = (0 until n).sortedWith(compareBy { na[it] }).toIntArray()

        val lenMap = HashMap<Int, ArrayList<Int>>()
        val gMap = HashMap<Int, ArrayList<Int>>()
        for (i in 0 until n) {
            lenMap.getOrPut(na[i].length) { ArrayList() }.add(i)
            val g = ga[i]
            if (g.length >= 2) gMap.getOrPut(bucketKey(g[0], g[g.length - 1])) { ArrayList() }.add(i)
        }

        words = wa; norms = na; gpaths = ga; freqs = fa; exact = ex; sortedIdx = sorted
        byLen = HashMap(lenMap.mapValues { it.value.toIntArray() })
        glideBuckets = HashMap(gMap.mapValues { it.value.toIntArray() })
        loadUser()
        loaded = true
    }

    // ---------------------------------------------------------------- normalization

    companion object {
        fun norm(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s.lowercase()) sb.append(
                when (ch) {
                    'á', 'à', 'â', 'ä', 'ã' -> 'a'
                    'é', 'è', 'ê', 'ë' -> 'e'
                    'í', 'ì', 'î', 'ï' -> 'i'
                    'ó', 'ò', 'ô', 'ö', 'õ' -> 'o'
                    'ú', 'ù', 'û', 'ü' -> 'u'
                    'ñ' -> 'n'
                    'ç' -> 'c'
                    else -> ch
                }
            )
            return sb.toString()
        }

        /** Like norm but keeps ñ (it's a real key for gliding). */
        fun glideNorm(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s.lowercase()) sb.append(if (ch == 'ñ') 'ñ' else norm(ch.toString())[0])
            return sb.toString()
        }

        fun dedupe(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s) if (sb.isEmpty() || sb[sb.length - 1] != ch) sb.append(ch)
            return sb.toString()
        }

        private fun bucketKey(a: Char, b: Char) = a.code * 65536 + b.code

        // keyboard grid for proximity (x in key units, y in rows)
        private val grid: Map<Char, Pair<Float, Float>> = HashMap<Char, Pair<Float, Float>>().apply {
            "qwertyuiop".forEachIndexed { i, ch -> put(ch, Pair(i + 0.5f, 0f)) }
            "asdfghjkl".forEachIndexed { i, ch -> put(ch, Pair(i + 0.5f, 1f)) }
            "zxcvbnm".forEachIndexed { i, ch -> put(ch, Pair(i + 2.0f, 2f)) }
        }

        fun near(a: Char, b: Char): Boolean {
            val pa = grid[a] ?: return false
            val pb = grid[b] ?: return false
            return hypot(pa.first - pb.first, pa.second - pb.second) < 1.6f
        }
    }

    // ---------------------------------------------------------------- validity

    @Synchronized
    fun isValid(word: String): Boolean {
        val lw = word.lowercase()
        return exact.containsKey(lw) || (userWords[lw] ?: 0) >= 2
    }

    // ---------------------------------------------------------------- edit distance

    private var r0 = FloatArray(64)
    private var r1 = FloatArray(64)
    private var r2 = FloatArray(64)

    private fun subCost(a: Char, b: Char): Float = if (near(a, b)) 0.6f else 1f

    private fun dist(a: String, b: String, max: Float): Float {
        val n = a.length
        val m = b.length
        if (abs(n - m) > max || m >= 63 || n >= 63) return Float.MAX_VALUE
        var prev2 = r0
        var prev = r1
        var cur = r2
        for (j in 0..m) prev[j] = j.toFloat()
        for (i in 1..n) {
            cur[0] = i.toFloat()
            var rowMin = cur[0]
            val ca = a[i - 1]
            for (j in 1..m) {
                val cb = b[j - 1]
                val sub = if (ca == cb) 0f else subCost(ca, cb)
                var v = min(min(prev[j] + 1f, cur[j - 1] + 1f), prev[j - 1] + sub)
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) v = min(v, prev2[j - 2] + 0.7f)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return Float.MAX_VALUE
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    // ---------------------------------------------------------------- suggestions

    private fun bigramBoost(prev: String?, word: String): Float {
        if (prev == null) return 0f
        val c = bigrams[prev]?.get(word.lowercase()) ?: return 0f
        return 0.9f * ln(1f + c)
    }

    private fun prefixRange(p: String): IntRange {
        var lo = 0
        var hi = sortedIdx.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (norms[sortedIdx[mid]] < p) lo = mid + 1 else hi = mid
        }
        val start = lo
        hi = sortedIdx.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (norms[sortedIdx[mid]].startsWith(p)) lo = mid + 1 else hi = mid
        }
        return start until lo
    }

    @Synchronized
    fun suggest(typed: String, prev: String?): Suggestions {
        val lw = typed.lowercase()
        val nt = norm(lw)
        val typedValid = isValid(lw)
        val comp = HashMap<String, Float>()   // completions
        val corr = HashMap<String, Float>()   // corrections (same-ish length)
        val corrDist = HashMap<String, Float>()
        val corrFreq = HashMap<String, Int>()

        fun put(map: HashMap<String, Float>, w: String, s: Float) {
            val old = map[w]
            if (old == null || s > old) map[w] = s
        }

        if (nt.isEmpty()) return Suggestions(listOf("", typed, ""), null, typedValid)

        // completions
        for (k in prefixRange(nt)) {
            val idx = sortedIdx[k]
            val extra = norms[idx].length - nt.length
            if (extra <= 0) continue
            put(comp, words[idx], freqs[idx] / 100f - 0.35f * extra - 0.8f + bigramBoost(prev, words[idx]))
        }

        // corrections
        if (nt.length >= 2) {
            val maxD = when {
                nt.length <= 3 -> 1.0f
                nt.length <= 6 -> 1.7f
                else -> 2.2f
            }
            for (len in (nt.length - 2)..(nt.length + 2)) {
                val arr = byLen[len] ?: continue
                for (idx in arr) {
                    // first letter is rarely wrong: cheap pre-filter
                    val f0 = norms[idx][0]
                    if (f0 != nt[0] && !near(f0, nt[0]) && !(nt.length > 1 && f0 == nt[1])) continue
                    val d = dist(nt, norms[idx], maxD)
                    if (d <= maxD) {
                        val s = freqs[idx] / 100f - d * 2.4f + (if (d == 0f) 1.5f else 0f) +
                            bigramBoost(prev, words[idx])
                        put(corr, words[idx], s)
                        val od = corrDist[words[idx]]
                        if (od == null || d < od) corrDist[words[idx]] = d
                        corrFreq[words[idx]] = freqs[idx]
                    }
                }
            }
        }

        // user words
        for ((w, c) in userWords) {
            if (c < 1 || exact.containsKey(w)) continue
            val nw = norm(w)
            val boost = 4.5f + ln(1f + c) + bigramBoost(prev, w)
            if (nw.startsWith(nt) && nw.length > nt.length) put(comp, w, boost - 0.3f * (nw.length - nt.length))
            else if (c >= 2 && nt.length >= 2) {
                val d = dist(nt, nw, 1.7f)
                if (d <= 1.7f) put(corr, w, boost - d * 2.4f + (if (d == 0f) 1.5f else 0f))
            }
        }

        comp.remove(lw); corr.remove(lw)

        val bestCorr = corr.entries.maxByOrNull { it.value }
        var auto: String? = null
        if (!typedValid && bestCorr != null && nt.length >= 2 && bestCorr.value > 1.2f) auto = bestCorr.key
        // rare dictionary entries are often typos found on the web ("qeu", "cmo"):
        // still correct them when a much more common word is one edit away
        val typedIdx = exact[lw]
        if (typedValid && typedIdx != null && freqs[typedIdx] < 300 && (userWords[lw] ?: 0) < 2 && bestCorr != null) {
            val bd = corrDist[bestCorr.key] ?: 9f
            val bf = corrFreq[bestCorr.key] ?: 0
            if (bd <= 1.0f && bf >= freqs[typedIdx] + 200) auto = bestCorr.key
        }

        val all = HashMap<String, Float>()
        for ((k, v) in comp) put(all, k, v)
        for ((k, v) in corr) put(all, k, v)
        val ranked = all.entries.sortedByDescending { it.value }.map { it.key }
            .filter { it != auto }

        val slots: List<String> = if (auto != null) {
            listOf(typed, auto, ranked.getOrElse(0) { "" })
        } else {
            listOf(ranked.getOrElse(0) { "" }, typed, ranked.getOrElse(1) { "" })
        }
        return Suggestions(slots, auto, typedValid)
    }

    /** Next-word predictions from what the user has typed before. */
    @Synchronized
    fun predict(prev: String?): List<String> {
        if (prev == null) return emptyList()
        val m = bigrams[prev] ?: return emptyList()
        return m.entries.sortedByDescending { it.value }.take(3).map { it.key }
    }

    // ---------------------------------------------------------------- glide

    private val N = 50
    private val ux = FloatArray(N)
    private val uy = FloatArray(N)
    private val ix = FloatArray(N)
    private val iy = FloatArray(N)
    private val tmpX = FloatArray(64)
    private val tmpY = FloatArray(64)

    private fun resample(px: FloatArray, py: FloatArray, cnt: Int, outX: FloatArray, outY: FloatArray) {
        if (cnt <= 0) return
        var total = 0f
        for (i in 1 until cnt) total += hypot(px[i] - px[i - 1], py[i] - py[i - 1])
        if (total <= 1e-4f || cnt == 1) {
            for (i in 0 until N) { outX[i] = px[0]; outY[i] = py[0] }
            return
        }
        val step = total / (N - 1)
        outX[0] = px[0]; outY[0] = py[0]
        var out = 1
        var acc = 0f
        var i = 1
        var lx = px[0]
        var ly = py[0]
        while (i < cnt && out < N) {
            val d = hypot(px[i] - lx, py[i] - ly)
            if (acc + d >= step && d > 0f) {
                val t = (step - acc) / d
                val nx = lx + t * (px[i] - lx)
                val ny = ly + t * (py[i] - ly)
                outX[out] = nx; outY[out] = ny; out++
                lx = nx; ly = ny
                acc = 0f
            } else {
                acc += d
                lx = px[i]; ly = py[i]
                i++
            }
        }
        while (out < N) { outX[out] = px[cnt - 1]; outY[out] = py[cnt - 1]; out++ }
    }

    /**
     * @param px,py  finger path in pixels
     * @param centers key centers (pixels) of letter keys, lowercase
     * @param kw,kh  key width / row height (pixels) – used to normalize distances
     */
    @Synchronized
    fun glide(
        px: FloatArray, py: FloatArray, cnt: Int,
        centers: Map<Char, FloatArray>, kw: Float, kh: Float, prev: String?
    ): List<String> {
        if (!loaded || cnt < 2) return emptyList()
        // normalize to key units
        val nx = FloatArray(cnt) { px[it] / kw }
        val ny = FloatArray(cnt) { py[it] / kh }
        resample(nx, ny, cnt, ux, uy)
        val cx = HashMap<Char, FloatArray>()
        for ((k, v) in centers) cx[k] = floatArrayOf(v[0] / kw, v[1] / kh)

        fun nearest(x: Float, y: Float): List<Pair<Char, Float>> =
            cx.entries.map { Pair(it.key, hypot(it.value[0] - x, it.value[1] - y)) }
                .filter { it.second < 1.15f }.sortedBy { it.second }.take(3)

        var userLen = 0f
        for (i in 1 until N) userLen += hypot(ux[i] - ux[i - 1], uy[i] - uy[i - 1])
        val starts = nearest(ux[0], uy[0])
        val ends = nearest(ux[N - 1], uy[N - 1])
        val scores = HashMap<String, Float>()

        fun consider(gp: String, display: String, freq: Float, sd: Float, ed: Float) {
            if (!passes(gp, cx)) return
            val loc = shapeDist(gp, cx)
            if (loc > 1.0f) return
            val miss = keyMiss(gp, cx)
            // ideal path much longer than what the finger drew = extra detours the user didn't make
            val lenRatio = if (userLen > 0.3f) max(0f, idealLen(gp, cx) / userLen - 1.1f) else 0f
            val s = freq * 0.55f - loc * 6.5f - miss * 3f - min(lenRatio, 1.5f) * 6f - (sd + ed) * 1.2f + bigramBoost(prev, display)
            val old = scores[display]
            if (old == null || s > old) scores[display] = s
        }

        for ((s, sd) in starts) for ((e, ed) in ends) {
            val bucket = glideBuckets[bucketKey(s, e)] ?: continue
            for (idx in bucket) consider(gpaths[idx], words[idx], freqs[idx] / 100f, sd, ed)
        }
        for ((w, c) in userWords) {
            if (c < 2 || exact.containsKey(w)) continue
            val gp = dedupe(glideNorm(w))
            if (gp.length < 2) continue
            val sd = starts.firstOrNull { it.first == gp[0] }?.second ?: continue
            val ed = ends.firstOrNull { it.first == gp[gp.length - 1] }?.second ?: continue
            consider(gp, w, 4.5f + ln(1f + c), sd, ed)
        }
        return scores.entries.sortedByDescending { it.value }.take(4).map { it.key }
    }

    private fun idealLen(gp: String, cx: Map<Char, FloatArray>): Float {
        var l = 0f
        for (i in 1 until gp.length) {
            val a = cx[gp[i - 1]] ?: continue
            val b = cx[gp[i]] ?: continue
            l += hypot(a[0] - b[0], a[1] - b[1])
        }
        return l
    }

    /** How far the path stays from keys the word needs (letters the finger never really reached). */
    private fun keyMiss(gp: String, cx: Map<Char, FloatArray>): Float {
        var total = 0f
        for (ch in gp) {
            val k = cx[ch] ?: continue
            var best = Float.MAX_VALUE
            for (j in 0 until N) {
                val d = hypot(ux[j] - k[0], uy[j] - k[1])
                if (d < best) best = d
            }
            if (best > 0.35f) total += best - 0.35f
        }
        return total
    }

    /** Every letter's key must be near the path, in order. */
    private fun passes(gp: String, cx: Map<Char, FloatArray>): Boolean {
        var j = 0
        for (ch in gp) {
            val k = cx[ch] ?: return false
            var found = false
            while (j < N) {
                if (hypot(ux[j] - k[0], uy[j] - k[1]) < 1.05f) { found = true; break }
                j++
            }
            if (!found) return false
        }
        return true
    }

    private fun shapeDist(gp: String, cx: Map<Char, FloatArray>): Float {
        val n = min(gp.length, 64)
        for (i in 0 until n) {
            val k = cx[gp[i]] ?: return 99f
            tmpX[i] = k[0]; tmpY[i] = k[1]
        }
        resample(tmpX, tmpY, n, ix, iy)
        var sum = 0f
        for (i in 0 until N) sum += hypot(ux[i] - ix[i], uy[i] - iy[i])
        return sum / N
    }

    // ---------------------------------------------------------------- learning

    @Synchronized
    fun learnWord(word: String) {
        val lw = word.lowercase()
        if (lw.length < 2 || exact.containsKey(lw) || !lw.all { it.isLetter() }) return
        userWords[lw] = (userWords[lw] ?: 0) + 1
        dirty = true
    }

    /** User explicitly kept this word (undid an autocorrection / tapped it). */
    @Synchronized
    fun forceLearn(word: String) {
        val lw = word.lowercase()
        if (lw.length < 2 || exact.containsKey(lw) || !lw.all { it.isLetter() }) return
        userWords[lw] = maxOf(2, (userWords[lw] ?: 0) + 1)
        dirty = true
    }

    @Synchronized
    fun learnBigram(prev: String, next: String) {
        val p = prev.lowercase()
        val n = next.lowercase()
        if (p.isEmpty() || n.isEmpty()) return
        val m = bigrams.getOrPut(p) { HashMap() }
        m[n] = (m[n] ?: 0) + 1
        if (m.size > 40) {
            val worst = m.entries.minByOrNull { it.value }
            if (worst != null) m.remove(worst.key)
        }
        if (bigrams.size > 6000) {
            val k = bigrams.keys.firstOrNull()
            if (k != null && k != p) bigrams.remove(k)
        }
        dirty = true
    }

    private fun loadUser() {
        val dir = userDir ?: return
        try {
            val uf = File(dir, "user_words.txt")
            if (uf.exists()) uf.forEachLine { line ->
                val t = line.split('\t')
                if (t.size == 2) userWords[t[0]] = t[1].toIntOrNull() ?: 1
            }
            val bf = File(dir, "bigrams.txt")
            if (bf.exists()) bf.forEachLine { line ->
                val t = line.split('\t')
                if (t.size == 3) bigrams.getOrPut(t[0]) { HashMap() }[t[1]] = t[2].toIntOrNull() ?: 1
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun saveAsync() {
        val dir = userDir ?: return
        if (!dirty) return
        dirty = false
        val uw = HashMap(userWords)
        val bg = HashMap<String, Map<String, Int>>()
        for ((k, v) in bigrams) bg[k] = HashMap(v)
        Thread {
            try {
                File(dir, "user_words.txt").writeText(uw.entries.joinToString("\n") { "${it.key}\t${it.value}" })
                val sb = StringBuilder()
                for ((p, m) in bg) for ((n, c) in m) sb.append(p).append('\t').append(n).append('\t').append(c).append('\n')
                File(dir, "bigrams.txt").writeText(sb.toString())
            } catch (_: Exception) {
            }
        }.start()
    }

    @Synchronized
    fun clearLearning() {
        userWords.clear()
        bigrams.clear()
        dirty = true
        saveAsync()
    }

    val learnedCount: Int
        @Synchronized get() = userWords.count { it.value >= 2 }
}
