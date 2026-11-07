package com.kydium.tecladoxp

import android.content.SharedPreferences

/**
 * Tunes the swipe recognizer with your own saved swipes (see [GlideLog]): tries small changes to
 * each weight and keeps only those that recognize more of your swipes. Half of the swipes are used
 * to choose and the other half to check, so it doesn't just memorize them.
 */
object Tuner {
    val names = listOf("gFreq", "gLoc", "gMiss", "gLen", "gEnds", "gPass")
    private val defaults = mapOf("gFreq" to 2f, "gLoc" to 8f, "gMiss" to 2f, "gLen" to 4f, "gEnds" to 4f, "gPass" to 1.4f)
    const val MIN_SAMPLES = 60

    fun get(n: String): Float = when (n) {
        "gFreq" -> Dictionary.gFreq
        "gLoc" -> Dictionary.gLoc
        "gMiss" -> Dictionary.gMiss
        "gLen" -> Dictionary.gLen
        "gEnds" -> Dictionary.gEnds
        else -> Dictionary.gPass
    }

    fun set(n: String, v: Float) {
        when (n) {
            "gFreq" -> Dictionary.gFreq = v
            "gLoc" -> Dictionary.gLoc = v
            "gMiss" -> Dictionary.gMiss = v
            "gLen" -> Dictionary.gLen = v
            "gEnds" -> Dictionary.gEnds = v
            else -> Dictionary.gPass = v
        }
    }

    /** Applies weights saved by an earlier tuning. */
    fun load(prefs: SharedPreferences) {
        for (n in names) if (prefs.contains("tune_$n")) set(n, prefs.getFloat("tune_$n", get(n)))
    }

    fun isTuned(prefs: SharedPreferences) = prefs.contains("tune_gFreq")

    fun reset(prefs: SharedPreferences) {
        val e = prefs.edit()
        for (n in names) {
            e.remove("tune_$n")
            set(n, defaults[n] ?: get(n))
        }
        e.remove("tune_result").apply()
    }

    class Result(val before: Int, val after: Int, val total: Int)

    /** Slow (a minute or two on the phone): run it on a background thread. */
    fun run(dict: Dictionary, prefs: SharedPreferences, progress: (Int, Int) -> Unit): Result? {
        val data = GlideLog.samples() ?: return null
        val cen = data.first
        val all = data.second.takeLast(800)
        if (all.size < MIN_SAMPLES) return null
        val start = names.associateWith { get(it) }
        fun score(part: Int): Int {
            var ok = 0
            for ((i, s) in all.withIndex()) {
                if (part >= 0 && i % 2 != part) continue
                val r = try { dict.glide(s.x, s.y, s.x.size, cen, 1f, 1f, s.ctx) } catch (_: Exception) { emptyList() }
                if (r.firstOrNull()?.lowercase() == s.label) ok++
            }
            return ok
        }
        dict.usePersonal = false
        try {
            val steps = names.size * 2 * 2 + 2
            var step = 0
            progress(step, steps)
            val before = score(-1)
            step++
            var train = score(0)
            var test = score(1)
            repeat(2) {
                for (n in names) {
                    val cur = get(n)
                    var bestV = cur
                    var bestTrain = train
                    for (f in floatArrayOf(0.8f, 1.25f)) {
                        set(n, cur * f)
                        val t = score(0)
                        if (t > bestTrain) { bestTrain = t; bestV = cur * f }
                        progress(++step, steps)
                    }
                    set(n, bestV)
                    if (bestV != cur) {
                        val te = score(1)
                        if (te >= test) { train = bestTrain; test = te } else set(n, cur)   // didn't help on the other half
                    }
                }
            }
            val after = score(-1)
            progress(steps, steps)
            if (after <= before) {
                for ((n, v) in start) set(n, v)
                return Result(before, before, all.size)
            }
            val e = prefs.edit()
            for (n in names) e.putFloat("tune_$n", get(n))
            e.putString("tune_result", "${100 * before / all.size}% → ${100 * after / all.size}%").apply()
            return Result(before, after, all.size)
        } catch (e: Exception) {
            for ((n, v) in start) set(n, v)
            CrashLog.log(e, "ajuste")
            return null
        } finally {
            dict.usePersonal = true
        }
    }
}
