package com.kydium.tecladoxp

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class WordKind { TYPED, GLIDE, SUGGESTION, AUTOCORRECT }

class Achievement(
    val id: String,
    val name: String,
    val desc: String,
    val xp: Int,
    val check: (XpManager) -> Boolean
)

class XpResult(
    val gained: Int,
    val leveledUp: Boolean,
    val level: Int,
    val combo: Int,
    val dailyBonus: Int,
    val achievements: List<Achievement>
)

object XpManager {
    /** No real cap: levels are infinite (each one needs a bit more XP). */
    const val MAX_LEVEL = Int.MAX_VALUE
    private const val COMBO_WINDOW_MS = 5000L

    private var prefs: SharedPreferences? = null

    var totalXp = 0L; private set
    var totalWords = 0L; private set
    var totalChars = 0L; private set
    var glideWords = 0L; private set
    var corrections = 0L; private set
    var suggestionsUsed = 0L; private set
    var bestCombo = 0; private set
    var streakDays = 0; private set
    var lastDay = 0L; private set
    var wordsToday = 0; private set
    var xpToday = 0L; private set
    var combo = 0; private set
    private var lastWordAt = 0L
    private val unlocked = HashSet<String>()

    val achievements: List<Achievement> = listOf(
        Achievement("w100", "Calentando dedos", "Escribe 100 palabras", 100) { it.totalWords >= 100 },
        Achievement("w1k", "Charlatán", "Escribe 1,000 palabras", 500) { it.totalWords >= 1_000 },
        Achievement("w10k", "Novelista", "Escribe 10,000 palabras", 2_000) { it.totalWords >= 10_000 },
        Achievement("w50k", "Enciclopedia andante", "Escribe 50,000 palabras", 5_000) { it.totalWords >= 50_000 },
        Achievement("g50", "Dedo deslizante", "Escribe 50 palabras deslizando", 200) { it.glideWords >= 50 },
        Achievement("g1k", "Patinador profesional", "Escribe 1,000 palabras deslizando", 1_000) { it.glideWords >= 1_000 },
        Achievement("c25", "En llamas", "Logra un combo de 25", 300) { it.bestCombo >= 25 },
        Achievement("c100", "Imparable", "Logra un combo de 100", 1_000) { it.bestCombo >= 100 },
        Achievement("s3", "Constante", "Escribe 3 días seguidos", 200) { it.streakDays >= 3 },
        Achievement("s7", "Semana perfecta", "Escribe 7 días seguidos", 700) { it.streakDays >= 7 },
        Achievement("s30", "Hábito de acero", "Escribe 30 días seguidos", 3_000) { it.streakDays >= 30 },
        Achievement("f100", "Salvado por la campana", "100 autocorrecciones", 300) { it.corrections >= 100 },
        Achievement("p200", "Lector de mentes", "Usa 200 sugerencias", 300) { it.suggestionsUsed >= 200 },
        Achievement("l10", "Escriba oficial", "Llega al nivel 10", 500) { it.level() >= 10 },
        Achievement("l25", "Veterano", "Llega al nivel 25", 1_500) { it.level() >= 25 },
        Achievement("l50", "Leyenda viva", "Llega al nivel 50", 5_000) { it.level() >= 50 }
    )

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("xp", Context.MODE_PRIVATE)
        prefs = p
        totalXp = p.getLong("totalXp", 0)
        totalWords = p.getLong("totalWords", 0)
        totalChars = p.getLong("totalChars", 0)
        glideWords = p.getLong("glideWords", 0)
        corrections = p.getLong("corrections", 0)
        suggestionsUsed = p.getLong("suggestionsUsed", 0)
        bestCombo = p.getInt("bestCombo", 0)
        streakDays = p.getInt("streakDays", 0)
        lastDay = p.getLong("lastDay", 0)
        wordsToday = p.getInt("wordsToday", 0)
        xpToday = p.getLong("xpToday", 0)
        unlocked.clear()
        unlocked.addAll(p.getStringSet("unlocked", emptySet()) ?: emptySet())
    }

    /** Reload from disk (the settings screen and the keyboard run in the same process, but be safe). */
    fun reload(ctx: Context) {
        prefs = null
        init(ctx)
    }

    // ---------- Levels ----------
    fun xpForNext(level: Int): Long = (100.0 * level.toDouble().pow(1.6) + 100.0).toLong()

    fun level(): Int = levelInfo().first

    /** (level, xp inside current level, xp needed for next level) */
    fun levelInfo(xp: Long = totalXp): Triple<Int, Long, Long> {
        var lvl = 1
        var rest = xp
        while (lvl < MAX_LEVEL) {
            val need = xpForNext(lvl)
            if (rest < need) return Triple(lvl, rest, need)
            rest -= need
            lvl++
        }
        return Triple(MAX_LEVEL, 0, 1)
    }

    fun progress(): Float {
        val (lvl, into, need) = levelInfo()
        if (lvl >= MAX_LEVEL) return 1f
        return (into.toFloat() / need.toFloat()).coerceIn(0f, 1f)
    }

    fun titleFor(level: Int): String = when {
        level >= 300 -> "Eterno"
        level >= 200 -> "Cósmico"
        level >= 150 -> "Inmortal"
        level >= 100 -> "Dios del Teclado"
        level >= 75 -> "Mítico"
        level >= 50 -> "Leyenda"
        level >= 40 -> "Gran Maestro"
        level >= 30 -> "Maestro"
        level >= 20 -> "Experto"
        level >= 15 -> "Veloz"
        level >= 10 -> "Escriba"
        level >= 6 -> "Aprendiz"
        level >= 3 -> "Tecleador"
        else -> "Novato"
    }

    fun comboMultiplier(c: Int = combo): Float = 1f + min(c, 50) / 50f

    fun isUnlocked(id: String) = unlocked.contains(id)

    // ---------- Events ----------
    fun onWord(len: Int, kind: WordKind, now: Long = System.currentTimeMillis()): XpResult {
        val levelBefore = level()

        // daily streak
        var daily = 0
        val today = LocalDate.now().toEpochDay()
        if (today != lastDay) {
            streakDays = if (lastDay == today - 1) streakDays + 1 else 1
            lastDay = today
            wordsToday = 0
            xpToday = 0
            daily = 50 + 10 * min(streakDays, 10)
        }

        // combo
        combo = if (now - lastWordAt <= COMBO_WINDOW_MS) combo + 1 else 1
        lastWordAt = now
        bestCombo = max(bestCombo, combo)

        // base xp
        var base = len + 2
        when (kind) {
            WordKind.GLIDE -> { base += 3; glideWords++ }
            WordKind.SUGGESTION -> { base += 2; suggestionsUsed++ }
            WordKind.AUTOCORRECT -> { base += 1; corrections++ }
            WordKind.TYPED -> {}
        }
        var gained = (base * comboMultiplier()).toInt() + daily

        totalWords++
        totalChars += len
        wordsToday++

        totalXp += gained

        // achievements (may cascade through level achievements)
        val newAch = ArrayList<Achievement>()
        var changed = true
        while (changed) {
            changed = false
            for (a in achievements) {
                if (!unlocked.contains(a.id) && a.check(this)) {
                    unlocked.add(a.id)
                    newAch.add(a)
                    totalXp += a.xp
                    gained += a.xp
                    changed = true
                }
            }
        }
        xpToday += gained

        val levelAfter = level()
        save()
        return XpResult(gained, levelAfter > levelBefore, levelAfter, combo, daily, newAch)
    }

    fun resetAll() {
        totalXp = 0; totalWords = 0; totalChars = 0; glideWords = 0; corrections = 0
        suggestionsUsed = 0; bestCombo = 0; streakDays = 0; lastDay = 0; wordsToday = 0
        xpToday = 0; combo = 0; lastWordAt = 0
        unlocked.clear()
        save()
    }

    private fun save() {
        val p = prefs ?: return
        p.edit()
            .putLong("totalXp", totalXp)
            .putLong("totalWords", totalWords)
            .putLong("totalChars", totalChars)
            .putLong("glideWords", glideWords)
            .putLong("corrections", corrections)
            .putLong("suggestionsUsed", suggestionsUsed)
            .putInt("bestCombo", bestCombo)
            .putInt("streakDays", streakDays)
            .putLong("lastDay", lastDay)
            .putInt("wordsToday", wordsToday)
            .putLong("xpToday", xpToday)
            .putStringSet("unlocked", HashSet(unlocked))
            .apply()
    }
}
