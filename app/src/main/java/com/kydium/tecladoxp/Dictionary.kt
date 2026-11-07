package com.kydium.tecladoxp

import java.io.File
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
    /** Words (3+ swipe letters) by first letter, for guessing a word before the swipe ends. */
    private var firstIdx: HashMap<Char, IntArray> = HashMap()

    /** Every word you commit, with how many times (dictionary words too: it's your personal ranking). */
    private val userWords = HashMap<String, Int>()
    private var userLoaded = false
    private val bigrams = HashMap<String, HashMap<String, Int>>()

    /**
     * Your own swipe shapes: for words you swiped and kept, the path you drew
     * (resampled to N points, in key units). A new swipe that looks like one of
     * these gets a big bonus for that word — it learns *your* way of swiping.
     */
    private val templates = LinkedHashMap<String, ArrayList<FloatArray>>()
    private val maxTemplateWords = 400
    private val templatesPerWord = 3
    @Volatile private var dirty = false

    // ---------------------------------------------------------------- loading

    /** Language(s) loaded: "es", "en" or "es_en". */
    @Volatile var lang = ""
        private set

    // Base n-gram model shipped with the app (from subtitles + treebanks): context -> (total, words, counts)
    private class Next(val total: Int, val words: Array<String>, val counts: IntArray)
    private var ngrams: HashMap<String, Next> = HashMap()

    @Synchronized
    fun load(input: InputStream) = load(listOf(Pair(input, 0)), emptyList(), "es")

    /**
     * @param sources word lists ("word<TAB>zipf*100"), each with a frequency offset
     *                (used to slightly prefer the main language in bilingual mode)
     * @param ngramSources n-gram files ("ctx<TAB>total<TAB>w1 c1 w2 c2…")
     */
    @Synchronized
    fun load(sources: List<Pair<InputStream, Int>>, ngramSources: List<InputStream>, language: String) {
        val merged = LinkedHashMap<String, Int>(110000)
        for ((input, offset) in sources) {
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab > 0) {
                        val word = line.substring(0, tab)
                        val fr = (line.substring(tab + 1).trim().toIntOrNull() ?: 300) + offset
                        val old = merged[word]
                        if (old == null || fr > old) merged[word] = fr
                    }
                }
            }
        }
        val w = ArrayList<String>(merged.keys)
        val f = ArrayList<Int>(merged.values)
        val ng = HashMap<String, Next>(60000)
        for (input in ngramSources) {
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val p = line.split('\t')
                    if (p.size == 3) {
                        val t = p[2].split(' ')
                        val n = t.size / 2
                        val ws = Array(n) { t[it * 2] }
                        val cs = IntArray(n) { t[it * 2 + 1].toIntOrNull() ?: 1 }
                        val total = p[1].toIntOrNull() ?: 1
                        val old = ng[p[0]]
                        if (old == null) ng[p[0]] = Next(total, ws, cs)   // first language wins (main one)
                    }
                }
            }
        }
        ngrams = ng
        lang = language
        val n = w.size
        val wa = w.toTypedArray()
        val na = Array(n) { norm(wa[it]) }
        val ga = Array(n) { dedupe(glideNorm(wa[it])) }
        val fa = IntArray(n) { f[it] }
        val ex = HashMap<String, Int>(n * 2)
        for (i in 0 until n) { val k = wa[i].lowercase(); if (!ex.containsKey(k)) ex[k] = i }
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
            if (b != i && fa[b] >= fa[i] + 70) ex.remove(wa[i].lowercase())
        }
        val sorted = (0 until n).sortedWith(compareBy { na[it] }).toIntArray()

        val lenMap = HashMap<Int, ArrayList<Int>>()
        val gMap = HashMap<Int, ArrayList<Int>>()
        val fMap = HashMap<Char, ArrayList<Int>>()
        for (i in 0 until n) {
            lenMap.getOrPut(na[i].length) { ArrayList() }.add(i)
            val g = ga[i]
            if (g.length >= 2) gMap.getOrPut(bucketKey(g[0], g[g.length - 1])) { ArrayList() }.add(i)
            if (g.length >= 3) fMap.getOrPut(g[0]) { ArrayList() }.add(i)
        }

        words = wa; norms = na; gpaths = ga; freqs = fa; exact = ex; sortedIdx = sorted
        byLen = HashMap(lenMap.mapValues { it.value.toIntArray() })
        glideBuckets = HashMap(gMap.mapValues { it.value.toIntArray() })
        firstIdx = HashMap(fMap.mapValues { it.value.toIntArray() })
        if (!userLoaded) loadUser()
        loaded = true
    }

    // ---------------------------------------------------------------- normalization

    companion object {
        @Volatile private var shared: Dictionary? = null

        // glide scoring weights (tuned on simulated finger paths)
        var gFreq = 1.5f
        var gLoc = 6.5f
        var gMiss = 3f
        var gLen = 6f
        var gEnds = 4f
        var gPass = 1.25f
        var tRadius = 0.6f
        var tScale = 14f

        /** One dictionary per app process, shared by the keyboard and the settings screen. */
        fun get(dir: File): Dictionary = shared ?: synchronized(this) {
            shared ?: Dictionary(dir).also { shared = it }
        }

        /** Not offered as a suggestion unless you've used it yourself (still accepted when typed). */
        private val OFFENSIVE = setOf(
            "puta", "puto", "putas", "putos", "mierda", "verga", "vergas", "pendejo", "pendeja", "cojudo", "cojuda",
            "concha", "carajo", "chucha", "culo", "culos", "coño", "joder", "polla", "huevón", "cabrón", "maricón",
            "marica", "zorra", "perra", "pinga", "mamón", "chingar", "chingada", "follar", "cagar", "cagada"
        )

        fun norm(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s.lowercase()) if (ch != '\'' && ch != '’') sb.append(
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
            for (ch in s.lowercase()) {
                if (ch == '\'' || ch == '’') continue
                sb.append(if (ch == 'ñ') 'ñ' else norm(ch.toString())[0])
            }
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
    /** Personal ranking bonus: words you use a lot float to the top. */
    private fun blocked(w: String): Boolean {
        val lw = w.lowercase()
        return OFFENSIVE.contains(lw) && (userWords[lw] ?: 0) < 2
    }

    private fun userBoost(w: String): Float {
        val c = userWords[w.lowercase()] ?: return 0f
        return 0.6f * ln(1f + c)
    }

    fun isValid(word: String): Boolean {
        val lw = word.lowercase()
        return exact.containsKey(lw) || (userWords[lw] ?: 0) >= 2
    }

    // ---------------------------------------------------------------- edit distance

    private var r0 = FloatArray(64)
    private var r1 = FloatArray(64)
    private var r2 = FloatArray(64)

    private fun subCost(a: Char, b: Char): Float = if (near(a, b)) 0.6f else 1f

    /**
     * Spatial model (like Gboard's): for each typed letter we know where the finger landed,
     * so swapping it for a key the finger almost touched is cheap and for a far key expensive.
     * tapCost[i][c] = cost of reading typed position i as letter c.
     */
    private var tapCost: Array<HashMap<Char, Float>?>? = null

    private fun buildTapCosts(taps: List<FloatArray?>?, centers: Map<Char, FloatArray>?, n: Int) {
        tapCost = null
        if (taps == null || centers == null || taps.size != n) return
        tapCost = Array(n) { i ->
            val t = taps[i] ?: return@Array null
            val m = HashMap<Char, Float>(32)
            for ((ch, cpos) in centers) {
                val d = hypot(t[0] - cpos[0], t[1] - cpos[1])
                val key = norm(ch.toString()).firstOrNull() ?: ch
                val cost = ((d - 0.35f) / 1.1f).coerceIn(0.05f, 1f)
                val old = m[key]
                if (old == null || cost < old) m[key] = cost
            }
            m
        }
    }

    private fun subCostAt(i: Int, a: Char, b: Char): Float {
        val tc = tapCost?.getOrNull(i) ?: return subCost(a, b)
        return tc[b] ?: 1f
    }

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
                val sub = if (ca == cb) 0f else subCostAt(i - 1, ca, cb)
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

    /**
     * Context bonus. [ctx] = the previous one or two words ("muchas", "te lo"), null at the
     * start of a sentence. Mixes the built-in n-gram model with what the user has typed.
     */
    private fun bigramBoost(ctx: String?, word: String): Float {
        val lw = word.lowercase()
        var b = 0f
        val last = ctx?.substringAfterLast(' ')
        if (last != null) bigrams[last]?.get(lw)?.let { b += 1.3f * ln(1f + it) }
        if (ctx != null && ctx.contains(' ')) bigrams[ctx]?.get(lw)?.let { b += 1.6f * ln(1f + it) }
        val key = ctx ?: "^"
        var p = 0f
        if (key.contains(' ')) ngrams[key]?.let { p = prob(it, lw) }
        if (p == 0f) ngrams[key.substringAfterLast(' ')]?.let { p = prob(it, lw) * 0.8f }
        if (p > 0f) b += 4.0f * sqrt(p)
        return b
    }

    private fun prob(n: Next, w: String): Float {
        for (i in n.words.indices) if (n.words[i] == w) return n.counts[i].toFloat() / n.total
        return 0f
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
    /**
     * @param taps finger positions (in key units) for each typed letter, or null
     * @param centers key centers (in key units) of the letter keys
     */
    fun suggest(typed: String, prev: String?, taps: List<FloatArray?>? = null, centers: Map<Char, FloatArray>? = null): Suggestions {
        val lw = typed.lowercase()
        val nt = norm(lw)
        buildTapCosts(taps, centers, nt.length)
        val typedValid = isValid(lw)
        val comp = HashMap<String, Float>()   // completions
        val corr = HashMap<String, Float>()   // corrections (same-ish length)
        val corrDist = HashMap<String, Float>()
        val corrFreq = HashMap<String, Int>()

        fun put(map: HashMap<String, Float>, w: String, s: Float) {
            if (blocked(w)) return
            val old = map[w]
            if (old == null || s > old) map[w] = s
        }

        if (nt.isEmpty()) return Suggestions(listOf("", typed, ""), null, typedValid)

        // completions
        for (k in prefixRange(nt)) {
            val idx = sortedIdx[k]
            val extra = norms[idx].length - nt.length
            if (extra <= 0) continue
            val ctxB = bigramBoost(prev, words[idx]) + userBoost(words[idx])
            put(comp, words[idx], freqs[idx] / 100f - 0.35f * extra - 0.8f + ctxB)
            // a word that is only 1-2 letters longer can also be the autocorrection
            // ("muchas graci" -> "gracias", "cómo est" -> "estás"), helped a lot by context
            if (extra <= 2 && nt.length >= 2) {
                val d = 0.6f * extra
                put(corr, words[idx], freqs[idx] / 100f - d * 2.4f + ctxB)
                val od = corrDist[words[idx]]
                if (od == null || d < od) corrDist[words[idx]] = d
                corrFreq[words[idx]] = freqs[idx]
            }
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
                            bigramBoost(prev, words[idx]) + userBoost(words[idx])
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

        // "i" -> "I", "im" handled above; a valid word whose dictionary spelling differs only in case
        if (auto == null && typedValid) {
            val disp = exact[lw]?.let { words[it] }
            if (disp != null && disp != typed && disp.lowercase() == lw && disp != lw) auto = disp
        }
        val slots: List<String> = when {
            auto != null -> listOf(typed, auto, ranked.getOrElse(0) { "" })
            // not a word and nothing to correct: the best completion goes in the middle
            !typedValid && ranked.isNotEmpty() -> listOf(typed, ranked[0], ranked.getOrElse(1) { "" })
            else -> listOf(ranked.getOrElse(0) { "" }, typed, ranked.getOrElse(1) { "" })
        }
        tapCost = null
        return Suggestions(slots, auto, typedValid)
    }

    /** Next-word predictions from what the user has typed before. */
    @Synchronized
    fun predict(ctx: String?): List<String> {
        val scores = HashMap<String, Float>()
        fun add(n: Next?, weight: Float) {
            n ?: return
            for (i in n.words.indices) {
                val w = n.words[i]
                scores[w] = (scores[w] ?: 0f) + weight * sqrt(n.counts[i].toFloat() / n.total)
            }
        }
        val key = ctx ?: "^"
        if (key.contains(' ')) add(ngrams[key], 3f)
        add(ngrams[key.substringAfterLast(' ')], 2f)
        val last = ctx?.substringAfterLast(' ')
        if (last != null) bigrams[last]?.forEach { (w, c) -> scores[w] = (scores[w] ?: 0f) + 1.3f * ln(1f + c) }
        if (ctx != null && ctx.contains(' ')) bigrams[ctx]?.forEach { (w, c) -> scores[w] = (scores[w] ?: 0f) + 1.8f * ln(1f + c) }
        return scores.entries.filter { !blocked(it.key) }
            .sortedByDescending { it.value }.take(3)
            .map { e -> exact[e.key]?.let { words[it] } ?: e.key }
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
        centers: Map<Char, FloatArray>, kw: Float, kh: Float, prev: String?,
        live: Boolean = false
    ): List<String> {
        if (!loaded || cnt < 2) return emptyList()
        // normalize to key units
        val nx = FloatArray(cnt) { px[it] / kw }
        val ny = FloatArray(cnt) { py[it] / kh }
        resample(nx, ny, cnt, ux, uy)
        // personal shapes: how close is this swipe to ones you made before?
        val personal = HashMap<String, Float>()
        for ((w, list) in templates) {
            var best = Float.MAX_VALUE
            for (t in list) {
                var sum = 0f
                for (i in 0 until N) sum += hypot(ux[i] - t[i * 2], uy[i] - t[i * 2 + 1])
                val d = sum / N
                if (d < best) best = d
            }
            if (best < tRadius) personal[w] = (tRadius - best) * tScale
        }
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
            if (blocked(display)) return
            val bonus = personal[display.lowercase()] ?: 0f
            if (bonus <= 0f && !passes(gp, cx)) return
            val loc = shapeDist(gp, cx)
            if (loc > 1.0f && bonus <= 0f) return
            val miss = keyMiss(gp, cx)
            // ideal path much longer than what the finger drew = extra detours the user didn't make
            val lenRatio = if (userLen > 0.3f) max(0f, idealLen(gp, cx) / userLen - 1.1f) else 0f
            val s = freq * gFreq - loc * gLoc - miss * gMiss - min(lenRatio, 1.5f) * gLen - (sd + ed) * gEnds +
                bigramBoost(prev, display) + userBoost(display) + bonus
            val old = scores[display]
            if (old == null || s > old) scores[display] = s
        }

        // try with the normal tolerance; if nothing fits (sloppy swipe), widen it
        for (radius in floatArrayOf(gPass, gPass + 0.35f, gPass + 0.75f)) {
            passRadius = radius
            for ((s, sd) in starts) for ((e, ed) in ends) {
                val bucket = glideBuckets[bucketKey(s, e)] ?: continue
                for (idx in bucket) consider(gpaths[idx], words[idx], freqs[idx] / 100f, sd, ed)
            }
            if (scores.isNotEmpty()) break
        }
        for ((w, c) in userWords) {
            if (c < 2 || exact.containsKey(w)) continue
            val gp = dedupe(glideNorm(w))
            if (gp.length < 2) continue
            val sd = starts.firstOrNull { it.first == gp[0] }?.second ?: continue
            val ed = ends.firstOrNull { it.first == gp[gp.length - 1] }?.second ?: continue
            consider(gp, w, 4.5f + ln(1f + c), sd, ed)
        }
        // while the finger is still moving: also words whose *beginning* matches the swipe so far
        // (so the word you want shows up before you reach all its letters, like Gboard)
        if (live) {
            // the finger is still moving: keep only "this word so far" matches
            scores.clear()
            passRadius = gPass
            val ex0 = ux[N - 1]
            val ey0 = uy[N - 1]
            for ((st, sd) in starts) {
                val arr = firstIdx[st] ?: continue
                for (idx in arr) {
                    val display = words[idx]
                    if (blocked(display)) continue
                    val gp = gpaths[idx]
                    val m = partialMatch(gp, cx, ex0, ey0) ?: continue
                    val (loc, done) = m
                    if (loc > 1.0f) continue
                    val sc = freqs[idx] / 100f * gFreq - loc * gLoc - sd * gEnds - 0.08f * (gp.length - done) +
                        bigramBoost(prev, display) + userBoost(display) + (personal[display.lowercase()] ?: 0f)
                    val old = scores[display]
                    if (old == null || sc > old) scores[display] = sc
                }
            }
            // 2-letter words
            for ((st, sd) in starts) for ((e, ed) in ends) {
                val bucket = glideBuckets[bucketKey(st, e)] ?: continue
                for (idx in bucket) if (gpaths[idx].length == 2) consider(gpaths[idx], words[idx], freqs[idx] / 100f, sd, ed)
            }
        }
        // words you swiped this way before, even if the generic matcher missed them
        for ((w, b) in personal) {
            val display = exact[w]?.let { words[it] } ?: w
            if (!scores.containsKey(display)) {
                val f = exact[w]?.let { freqs[it] / 100f } ?: 4.5f
                scores[display] = f * gFreq + b - 3f + bigramBoost(prev, display)
            }
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

    /**
     * Where is the finger along word [gp]? Returns (shape distance, letters reached) if the
     * swipe so far looks like the beginning of the word — the finger may be between two letters.
     */
    private fun partialMatch(gp: String, cx: Map<Char, FloatArray>, ex: Float, ey: Float): Pair<Float, Int>? {
        var best: Pair<Float, Int>? = null
        for (seg in 1 until gp.length) {
            val a = cx[gp[seg - 1]] ?: return null
            val b = cx[gp[seg]] ?: return null
            val vx = b[0] - a[0]
            val vy = b[1] - a[1]
            val len2 = vx * vx + vy * vy
            var t = if (len2 > 0f) ((ex - a[0]) * vx + (ey - a[1]) * vy) / len2 else 0f
            t = t.coerceIn(0f, 1f)
            val px = a[0] + vx * t
            val py = a[1] + vy * t
            if (hypot(ex - px, ey - py) > 0.75f) continue
            if (!passes(gp.substring(0, seg), cx)) continue
            // ideal path: letters reached so far + the point where the finger is now
            for (i in 0 until seg) { val k = cx[gp[i]]!!; tmpX[i] = k[0]; tmpY[i] = k[1] }
            tmpX[seg] = px; tmpY[seg] = py
            resample(tmpX, tmpY, seg + 1, ix, iy)
            var sum = 0f
            for (i in 0 until N) sum += hypot(ux[i] - ix[i], uy[i] - iy[i])
            val r = Pair(sum / N, if (t > 0.85f) seg + 1 else seg)
            if (best == null || r.first < best.first) best = r
        }
        return best
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

    private var passRadius = 1.05f

    /** Every letter's key must be near the path, in order. */
    private fun passes(gp: String, cx: Map<Char, FloatArray>): Boolean {
        var j = 0
        for (ch in gp) {
            val k = cx[ch] ?: return false
            var found = false
            while (j < N) {
                if (hypot(ux[j] - k[0], uy[j] - k[1]) < passRadius) { found = true; break }
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
        if (lw.isEmpty() || !lw.all { it.isLetter() || it == '\'' }) return
        if (lw.length < 2 && !exact.containsKey(lw)) return
        userWords[lw] = (userWords[lw] ?: 0) + 1
        if (userWords.size > 8000) {
            // forget the words used only once
            val it = userWords.entries.iterator()
            while (it.hasNext() && userWords.size > 6000) if (it.next().value <= 1) it.remove()
        }
        dirty = true
    }

    @Synchronized
    fun forceLearn(word: String) {
        val lw = word.lowercase()
        if (lw.length < 2 || !lw.all { it.isLetter() || it == '\'' }) return
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
        if (bigrams.size > 20000) {
            val k = bigrams.keys.firstOrNull()
            if (k != null && k != p) bigrams.remove(k)
        }
        dirty = true
    }

    /**
     * Learns from a block of your own text (e.g. an exported WhatsApp chat):
     * your words, which word follows which, and 3-word patterns.
     * @return number of words learned
     */
    @Synchronized
    fun learnText(text: String): Int {
        if (!userLoaded) loadUser()
        val tok = Regex("[\\p{L}]+(?:'[\\p{L}]+)?")
        var count = 0
        for (sentence in text.lowercase().split(Regex("[.!?¡¿\\n…]+"))) {
            var p2: String? = null
            var p1: String? = null
            for (m in tok.findAll(sentence)) {
                val w = m.value
                if (w.length > 30) { p1 = null; p2 = null; continue }
                learnWord(w)
                if (p1 != null) {
                    learnBigram(p1, w)
                    if (p2 != null) learnBigram("$p2 $p1", w)
                }
                p2 = p1
                p1 = w
                count++
            }
        }
        dirty = true
        return count
    }

    private fun loadUser() {
        userLoaded = true
        val dir = userDir ?: return
        try {
            val uf = File(dir, "user_words.txt")
            if (uf.exists()) uf.forEachLine { line ->
                val t = line.split('\t')
                if (t.size == 2) userWords[t[0]] = t[1].toIntOrNull() ?: 1
            }
            val tf = File(dir, "glide_shapes.txt")
            if (tf.exists()) tf.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val vals = line.substring(tab + 1).split(',').mapNotNull { it.toFloatOrNull() }
                    if (vals.size == N * 2) templates.getOrPut(line.substring(0, tab)) { ArrayList() }.add(vals.toFloatArray())
                }
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
        val tp = ArrayList<Pair<String, FloatArray>>()
        for ((w, list) in templates) for (t in list) tp.add(Pair(w, t.copyOf()))
        Thread {
            try {
                File(dir, "user_words.txt").writeText(uw.entries.joinToString("\n") { "${it.key}\t${it.value}" })
                val sb = StringBuilder()
                for ((p, m) in bg) for ((n, c) in m) sb.append(p).append('\t').append(n).append('\t').append(c).append('\n')
                File(dir, "bigrams.txt").writeText(sb.toString())
                val ts = StringBuilder()
                for ((w, t) in tp) {
                    ts.append(w).append('\t')
                    for (i in t.indices) { if (i > 0) ts.append(','); ts.append(String.format(java.util.Locale.US, "%.2f", t[i])) }
                    ts.append('\n')
                }
                File(dir, "glide_shapes.txt").writeText(ts.toString())
            } catch (_: Exception) {
            }
        }.start()
    }

    /** Remember the shape you drew for [word] (call when you keep a swiped word). */
    @Synchronized
    fun learnGlide(word: String, px: FloatArray, py: FloatArray, cnt: Int, kw: Float, kh: Float) {
        val lw = word.lowercase()
        if (cnt < 2 || lw.length < 2 || kw <= 0f || kh <= 0f) return
        val nx = FloatArray(cnt) { px[it] / kw }
        val ny = FloatArray(cnt) { py[it] / kh }
        val ox = FloatArray(N)
        val oy = FloatArray(N)
        resample(nx, ny, cnt, ox, oy)
        val t = FloatArray(N * 2)
        for (i in 0 until N) { t[i * 2] = ox[i]; t[i * 2 + 1] = oy[i] }
        val list = templates.remove(lw) ?: ArrayList()
        list.add(t)
        while (list.size > templatesPerWord) list.removeAt(0)
        templates[lw] = list                     // most recent at the end
        while (templates.size > maxTemplateWords) templates.remove(templates.keys.first())
        dirty = true
    }

    @Synchronized
    fun ensureUser() {
        if (!userLoaded) loadUser()
    }

    /** Words you taught it that are not in the dictionary. */
    @Synchronized
    fun myWords(): List<String> =
        userWords.entries.filter { it.value >= 2 && !exact.containsKey(it.key) }
            .sortedByDescending { it.value }.map { it.key }

    @Synchronized
    fun addWord(word: String): Boolean {
        val lw = word.trim().lowercase()
        if (lw.length < 2 || !lw.all { it.isLetter() || it == '\'' }) return false
        userWords[lw] = maxOf(6, userWords[lw] ?: 0)
        dirty = true
        saveAsync()
        return true
    }

    @Synchronized
    fun removeWord(word: String) {
        userWords.remove(word.lowercase())
        dirty = true
        saveAsync()
    }

    @Synchronized
    fun clearLearning() {
        userWords.clear()
        bigrams.clear()
        templates.clear()
        dirty = true
        saveAsync()
    }

    val learnedShapes: Int
        @Synchronized get() = templates.size

    val learnedCount: Int
        @Synchronized get() = userWords.count { it.value >= 2 && !exact.containsKey(it.key) }
}
