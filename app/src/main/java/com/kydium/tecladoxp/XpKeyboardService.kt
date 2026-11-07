package com.kydium.tecladoxp

import android.content.Context
import android.content.Intent
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import java.util.Locale
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

class XpKeyboardService : InputMethodService(), KeyboardView.Listener {

    private var kv: KeyboardView? = null
    private lateinit var dict: Dictionary
    private lateinit var settings: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: Handler
    private var workerThread: HandlerThread? = null
    private var vibrator: Vibrator? = null
    private var sounds: SoundFx? = null
    private var soundOn = true

    private val composing = StringBuilder()
    /** Finger position (key units) of each letter in [composing]; null for letters not tapped. */
    private val taps = ArrayList<FloatArray?>()
    private var pendingTap: FloatArray? = null

    private fun tapsFor(word: String): List<FloatArray?>? =
        if (taps.size == word.length && taps.any { it != null }) ArrayList(taps) else null

    private fun keyCenters(): Map<Char, FloatArray>? {
        val v = kv ?: return null
        val m = HashMap<Char, FloatArray>()
        for ((k, c) in v.letterCenters()) m[k] = floatArrayOf(c[0] / v.unitW, c[1] / v.keyRowH)
        return m
    }

    // per-field flags
    private var predictionsOn = true
    private var autoCorrectOn = true
    private var noLearn = false
    private var directMode = false      // commit letters without composing (passwords, numbers…)
    private var rawWordLen = 0          // letters typed in direct mode (for XP)

    // user settings
    private var vibrateOn = true
    private var vibStrength = 1         // 0 soft, 1 medium, 2 strong
    private var keySoundOn = false
    private var audio: AudioManager? = null
    private var doubleSpaceOn = true
    private var autoCorrectSetting = true
    private var glideSetting = true
    private var autoCapsOn = true
    private var nextWordOn = true
    private var emojiSugOn = true

    // first row: toolbar until you start typing, paste chip for something you just copied
    private var typedSinceStart = false
    private var chipText: String? = null
    private var chipStamp = 0L
    private val chipMs = 3 * 60_000L
    private val chipExpire = Runnable { CrashLog.safe("chip") { refreshSuggestions() } }
    private val refreshRunnable = Runnable { CrashLog.safe("sugerencias") { refreshSuggestions() } }

    // editing state
    private var lastWordWasGlide = false
    private var lastCorrection: Pair<String, String>? = null   // (typed, corrected)
    private var autoSpace = false
    private var lastSpaceTime = 0L
    private var prevWord: String? = null
    private var prevWord2: String? = null

    /**
     * Takes the previous one or two words from the text itself, so predictions stay right after
     * moving the cursor, pasting, or when the app changes the text (for example after sending).
     */
    private fun resyncContext() {
        val ic = ic() ?: return
        val before = try { ic.getTextBeforeCursor(80, 0)?.toString() } catch (_: Exception) { null } ?: return
        val comp = composing.toString()
        val text = if (comp.isNotEmpty() && before.endsWith(comp)) before.substring(0, before.length - comp.length) else before
        val words = ArrayList<String>(2)
        var i = text.length
        while (words.size < 2 && i > 0) {
            while (i > 0 && (text[i - 1] == ' ' || text[i - 1] == '\t' || text[i - 1] in ",;:\"«»“”()")) i--
            if (i == 0 || !isWordChar(text[i - 1])) break      // start of sentence, emoji, new line…
            var j = i
            while (j > 0 && isWordChar(text[j - 1])) j--
            val word = text.substring(j, i)
            if (word.none { it.isLetterOrDigit() }) break    // a lone "-" or "'"
            words.add(word.lowercase())
            i = j
        }
        prevWord = words.getOrNull(0)
        prevWord2 = words.getOrNull(1)
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

    /** Previous one or two words for predictions ("te lo"), null at the start of a sentence. */
    private fun context(): String? {
        val p = prevWord ?: return null
        val p2 = prevWord2
        return if (p2 != null) "$p2 $p" else p
    }

    // ================================================================= languages

    private fun langSetting(): String = settings.getString("lang", "es") ?: "es"

    /** Loads the dictionary for the chosen language(s) if it isn't loaded yet. */
    private fun ensureLanguage() {
        val want = langSetting()
        if (dict.loaded && dict.lang == want) return
        worker.post {
            try {
                if (!(dict.loaded && dict.lang == want)) loadLanguage(want)
            } catch (e: Exception) {
                CrashLog.log(e, "diccionario")
            }
            main.post {
                CrashLog.safe("idioma") {
                    kv?.spaceLabel = langLabel(want)
                    kv?.invalidate()
                    refreshSuggestions()
                }
            }
        }
    }

    private fun loadLanguage(lang: String) {
        val sources = ArrayList<Pair<java.io.InputStream, Int>>()
        val ngrams = ArrayList<java.io.InputStream>()
        when (lang) {
            "en" -> { sources.add(Pair(assets.open("en.txt"), 0)); ngrams.add(assets.open("ngram_en.txt")) }
            "es_en" -> {
                sources.add(Pair(assets.open("es.txt"), 0)); sources.add(Pair(assets.open("en.txt"), -30))
                ngrams.add(assets.open("ngram_es.txt")); ngrams.add(assets.open("ngram_en.txt"))
            }
            else -> { sources.add(Pair(assets.open("es.txt"), 0)); ngrams.add(assets.open("ngram_es.txt")) }
        }
        try {
            dict.load(sources, ngrams, lang)
        } finally {
            sources.forEach { try { it.first.close() } catch (_: Exception) {} }
            ngrams.forEach { try { it.close() } catch (_: Exception) {} }
        }
    }

    private fun langLabel(lang: String) = when (lang) {
        "en" -> "English"
        "es_en" -> "Español · English"
        else -> "Español"
    }
    private var lastShiftTime = 0L
    private var selStart = 0
    private var selEnd = 0

    private var sugFor: String? = null
    private var sugResult: Suggestions? = null
    private var slots: List<String> = listOf("", "", "")

    // ================================================================= lifecycle

    override fun onCreate() {
        super.onCreate()
        CrashLog.init(this)
        GlideLog.init(this)
        XpManager.init(this)
        TouchModel.init(this)
        settings = getSharedPreferences("settings", Context.MODE_PRIVATE)
        Tuner.load(settings)
        clipboard = getSystemService(ClipboardManager::class.java)
        loadClips()
        try { clipboard?.addPrimaryClipChangedListener(clipListener) } catch (_: Exception) {}
        dict = Dictionary.get(filesDir)
        val th = HandlerThread("tecladoxp-dict")
        th.start()
        workerThread = th
        worker = Handler(th.looper)
        ensureLanguage()
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    override fun onDestroy() {
        dict.saveAsync()
        sounds?.release()
        try { clipboard?.removePrimaryClipChangedListener(clipListener) } catch (_: Exception) {}
        recognizer?.destroy()
        recognizer = null
        workerThread?.quitSafely()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val v = KeyboardView(this, this)
        kv = v
        applySettings()
        refreshXp()
        return v
    }

    private fun applySettings() {
        val v = kv ?: return
        v.theme = Themes.byId(settings.getString("theme", "noche"))
            .let { if (it.unlockLevel <= XpManager.level()) it else Themes.all[0] }
        val lvl = XpManager.level()
        v.xpStyle = XpBarStyles.byId(settings.getString("xpstyle", "clasica"))
            .let { if (it.unlockLevel <= lvl) it.id else "clasica" }
        v.trailStyle = TrailStyles.byId(settings.getString("trailstyle", "clasica"))
            .let { if (it.unlockLevel <= lvl) it.id else "clasica" }
        v.heightScale = when (settings.getInt("height", 1)) { 4 -> 0.78f; 0 -> 0.88f; 2 -> 1.14f; 3 -> 1.28f; else -> 1f }
        v.showHints = settings.getBoolean("hints", true)
        v.showFloatingXp = settings.getBoolean("floatxp", true)
        v.showPreview = settings.getBoolean("preview", true)
        v.longPressMs = settings.getInt("longpress", 320).toLong()
        v.numberRow = settings.getBoolean("numrow", false)
        val oneHand = settings.getInt("onehand", 0)
        v.oneHandSide = if (oneHand > 0) oneHand else settings.getInt("onehand_side", 2)
        v.oneHand = oneHand
        v.spaceLabel = langLabel(langSetting())
        if (::worker.isInitialized) ensureLanguage()
        vibrateOn = settings.getBoolean("vibrate", true)
        vibStrength = settings.getInt("vibstrength", 1)
        keySoundOn = settings.getBoolean("keysound", false)
        autoCapsOn = settings.getBoolean("autocaps", true)
        nextWordOn = settings.getBoolean("nextword", true)
        emojiSugOn = settings.getBoolean("emojisug", true)
        soundOn = settings.getBoolean("xpsound", true)
        if (soundOn) (sounds ?: SoundFx(this).also { sounds = it }).preload()
        doubleSpaceOn = settings.getBoolean("doublespace", true)
        autoCorrectSetting = settings.getBoolean("autocorrect", true)
        glideSetting = settings.getBoolean("glide", true)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        CrashLog.safe("inicio") { startInput(info, restarting) }
    }

    private fun startInput(info: EditorInfo, restarting: Boolean) {
        val v = kv ?: return
        applySettings()
        if (!restarting) typedSinceStart = false
        v.clearToolbarOverride()
        composing.setLength(0)
        lastCorrection = null
        autoSpace = false
        lastWordWasGlide = false
        prevWord = null
        prevWord2 = null
        rawWordLen = 0
        selStart = info.initialSelStart
        selEnd = info.initialSelEnd

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        predictionsOn = true
        autoCorrectOn = autoCorrectSetting
        noLearn = false
        directMode = false
        var startLayout = LayoutType.LETTERS
        when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> {
                startLayout = LayoutType.SYMBOLS
                predictionsOn = false; autoCorrectOn = false; noLearn = true; directMode = true
            }
            InputType.TYPE_CLASS_TEXT -> {
                if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                ) {
                    predictionsOn = false; autoCorrectOn = false; noLearn = true; directMode = true
                }
                if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_URI ||
                    variation == InputType.TYPE_TEXT_VARIATION_FILTER
                ) {
                    autoCorrectOn = false
                }
                if (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) {
                    autoCorrectOn = false
                }
            }
            else -> {}
        }
        if (cls == 0) { // TYPE_NULL: raw key events, keep things simple
            predictionsOn = false; autoCorrectOn = false; directMode = true
        }
        v.glideEnabled = glideSetting && !directMode
        v.setLayout(startLayout)

        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        v.enterLabel = if (noEnterAction) "↵" else when (action) {
            EditorInfo.IME_ACTION_GO -> "➜"
            EditorInfo.IME_ACTION_SEARCH -> "⌕"
            EditorInfo.IME_ACTION_SEND -> "➤"
            EditorInfo.IME_ACTION_NEXT -> "⇥"
            EditorInfo.IME_ACTION_DONE -> "✓"
            EditorInfo.IME_ACTION_PREVIOUS -> "⇤"
            else -> "↵"
        }
        v.shiftState = 0
        captureClip()
        updateShiftAuto()
        refreshXp()
        refreshSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        CrashLog.safe("cierre") {
            main.removeCallbacks(refreshRunnable)
            cancelVoice()
            flushXp()
            TouchModel.save()
            composing.setLength(0)
            lastCorrection = null
            dict.saveAsync()
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        CrashLog.safe("cursor") {
            selStart = newSelStart
            selEnd = newSelEnd
            val movedAway = newSelStart != newSelEnd ||
                (candidatesEnd >= 0 && newSelStart != candidatesEnd) ||
                (candidatesEnd < 0 && (newSelStart < oldSelStart || newSelStart > oldSelStart + 3))
            if (composing.isNotEmpty() && movedAway && !listening) {
                // user moved the cursor away from the word being typed
                composing.setLength(0)
                currentInputConnection?.finishComposingText()
                lastWordWasGlide = false
                lastCorrection = null
            }
            if (composing.isEmpty()) {
                updateShiftAuto()
                // the text around the cursor changed: update predictions / toolbar (once per burst)
                main.removeCallbacks(refreshRunnable)
                main.postDelayed(refreshRunnable, 40)
            }
        }
    }

    // ================================================================= helpers

    private fun ic(): InputConnection? = currentInputConnection

    private fun updateShiftAuto() {
        val v = kv ?: return
        if (v.shiftState == 2 || !autoCapsOn) return
        if (v.layout.type != LayoutType.LETTERS) return
        val ic = ic() ?: return
        val ei = currentInputEditorInfo ?: return
        val caps = if (ei.inputType != 0) ic.getCursorCapsMode(ei.inputType) else 0
        v.shiftState = if (caps != 0) 1 else 0
    }

    private fun vibrate(ms: Long = 14) {
        if (!vibrateOn) return
        val vb = vibrator ?: return
        try {
            val (scale, strength) = when (vibStrength) { 0 -> Pair(0.7f, 40); 2 -> Pair(1.4f, 200); else -> Pair(1f, 90) }
            val amp = if (vb.hasAmplitudeControl()) strength else VibrationEffect.DEFAULT_AMPLITUDE
            vb.vibrate(VibrationEffect.createOneShot(maxOf(5L, (ms * scale).toLong()), amp))
        } catch (_: Exception) {
        }
    }

    /** Typing sound of the phone (only when the phone isn't on silent / vibrate). */
    private fun keyClick(code: Int) {
        if (!keySoundOn) return
        try {
            val am = audio ?: (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.also { audio = it } ?: return
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            val fx = when (code) {
                Codes.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                Codes.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                Codes.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            am.playSoundEffect(fx, -1f)
        } catch (_: Exception) {
        }
    }

    private fun matchCase(typed: String, word: String): String {
        if (word.isEmpty() || typed.isEmpty()) return word
        return when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }
    }

    private fun applyShiftToWord(word: String): String {
        val v = kv ?: return word
        return when (v.shiftState) {
            2 -> word.uppercase()
            1 -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }
    }

    /** Autocorrect only "real" words: not after @, #, digits, inside URLs… */
    private fun correctionAllowed(): Boolean {
        if (!autoCorrectOn) return false
        val ic = ic() ?: return false
        val before = ic.getTextBeforeCursor(composing.length + 1, 0)?.toString() ?: return true
        if (before.length <= composing.length) return true
        val c = before[0]
        return c.isWhitespace() || c in "¿¡(\"'«“"
    }

    private fun refreshXp() {
        val v = kv ?: return
        val (lvl, into, need) = XpManager.levelInfo()
        val frac = if (lvl >= XpManager.MAX_LEVEL) 1f else into.toFloat() / need
        // like GTA: total XP / total XP needed for the next level
        val line = "${XpManager.totalXp} / ${XpManager.totalFor(lvl + 1)}"
        val today = java.time.LocalDate.now().toEpochDay()
        val streak = if (XpManager.lastDay >= today - 1) XpManager.streakDays else 0
        v.setXp(lvl, frac, line, XpManager.combo, streak)
    }

    private fun refreshSuggestions() {
        val v = kv ?: return
        if (listening) return            // dictating: the bar stays as it is
        main.removeCallbacks(refreshRunnable)
        updateStrip()
        if (!predictionsOn || !dict.loaded) {
            slots = listOf("", "", "")
            v.setSuggestions(slots, -1)
            return
        }
        if (composing.isNotEmpty()) {
            if (lastWordWasGlide) return  // glide alternatives already shown
            val snapshot = composing.toString()
            val prev = context()
            val tp = tapsFor(snapshot)
            val centers = keyCenters()
            worker.removeCallbacksAndMessages(null)
            worker.post {
                val r = try { dict.suggest(snapshot, prev, tp, centers) } catch (e: Exception) { CrashLog.log(e, "sugerir"); null }
                main.post {
                    CrashLog.safe("sugerir") {
                        if (r != null && composing.toString() == snapshot && !lastWordWasGlide) {
                            sugFor = snapshot
                            sugResult = r
                            showSuggestions(snapshot, r)
                        }
                    }
                }
            }
        } else {
            sugFor = null
            sugResult = null
            resyncContext()
            val p = if (nextWordOn) dict.predict(context()) else emptyList()
            val cased = p.map { if ((kv?.shiftState ?: 0) > 0) it.replaceFirstChar { c -> c.uppercaseChar() } else it }
            slots = listOf(cased.getOrElse(1) { "" }, cased.getOrElse(0) { "" }, cased.getOrElse(2) { "" })
            v.setSuggestions(slots, -1)
        }
    }

    // ================================================================= first row: toolbar & paste chip

    /**
     * Before you type anything (and when the field is empty or text is selected) the first row shows
     * the toolbar: clipboard, text editing, one hand and settings. Something you just copied shows
     * up as a chip to paste it with one tap.
     */
    private fun updateStrip() {
        val v = kv ?: return
        if (composing.isNotEmpty()) {
            v.setStrip(false, null)
            return
        }
        val ic = ic()
        val fieldEmpty = ic != null &&
            ic.getTextBeforeCursor(1, 0).isNullOrEmpty() && ic.getTextAfterCursor(1, 0).isNullOrEmpty()
        val toolbar = !typedSinceStart || fieldEmpty || selStart != selEnd || !nextWordOn || !predictionsOn
        v.setStrip(toolbar, freshChip())
    }

    private fun freshChip(): String? {
        val t = chipText ?: return null
        if (System.currentTimeMillis() - chipStamp > chipMs) {
            chipText = null
            return null
        }
        return t
    }

    /** Offer [text] (copied at [stamp], wall-clock ms) as a chip, unless it's old or was already used. */
    private fun armChip(text: String, stamp: Long) {
        val now = System.currentTimeMillis()
        if (stamp <= 0L || now - stamp > chipMs || stamp > now + 60_000) return
        if (text.hashCode() == settings.getInt("chip_used_h", 0) && kotlin.math.abs(stamp - settings.getLong("chip_used", 0L)) < 10_000) return
        if (chipText == text && kotlin.math.abs(chipStamp - stamp) < 10_000) return
        chipText = text
        chipStamp = stamp
        main.removeCallbacks(chipExpire)
        main.postDelayed(chipExpire, chipMs - (now - stamp) + 100)
    }

    /** The chip was pasted or you started typing: don't offer it again. */
    private fun consumeChip() {
        val t = chipText ?: return
        chipText = null
        main.removeCallbacks(chipExpire)
        settings.edit().putLong("chip_used", chipStamp).putInt("chip_used_h", t.hashCode()).apply()
    }

    /** You typed something: show suggestions instead of the toolbar / chip. */
    private fun startedTyping() {
        typedSinceStart = true
        if (chipText != null) consumeChip()
        kv?.let {
            it.clearToolbarOverride()
            it.setStrip(false, null)
        }
    }

    override fun onClipChip() {
        val ic = ic() ?: return
        val text = freshChip() ?: return
        consumeChip()
        if (composing.isNotEmpty()) commitWord(false)
        ic.commitText(text, 1)
        typedSinceStart = true
        lastCorrection = null
        autoSpace = false
        updateShiftAuto()
        refreshSuggestions()
    }

    private fun showSuggestions(typed: String, r: Suggestions) {
        val v = kv ?: return
        val allowed = r.autoCorrect != null && correctionAllowed()
        val out = r.slots.map { s ->
            if (s.isEmpty()) s
            else if (s == typed) s
            else matchCase(typed, s)
        }.toMutableList()
        v.setStrip(false, null)
        if (emojiSugOn && autoCorrectOn) {
            // like Gboard: an emoji for the word in the right slot (tapping it replaces the word)
            val e = EmojiSuggest.forWord(typed) ?: r.autoCorrect?.let { if (allowed) EmojiSuggest.forWord(it) else null }
            if (e != null && out.size == 3) out[2] = e
        }
        slots = out
        v.setSuggestions(slots, if (allowed) 1 else -1)
    }

    private fun currentSuggestions(): Suggestions? {
        val typed = composing.toString()
        if (sugFor == typed && sugResult != null) return sugResult
        if (!dict.loaded) return null
        val r = dict.suggest(typed, context(), tapsFor(typed), keyCenters())
        sugFor = typed
        sugResult = r
        return r
    }

    /** XP is collected while you type and added all at once after a short pause. */
    private val flushXpRunnable = Runnable { CrashLog.safe("xp") { flushXp() } }
    private val xpPauseMs: Long get() = settings.getInt("xppause", 1000).toLong()

    private fun gainXp(word: String, kind: WordKind) {
        val letters = word.count { it.isLetter() }
        if (letters == 0 || noLearn) return
        XpManager.onWord(letters, kind)
        kv?.setPendingXp(XpManager.pendingXp)
        main.removeCallbacks(flushXpRunnable)
        main.postDelayed(flushXpRunnable, xpPauseMs)
    }

    private fun flushXp() {
        main.removeCallbacks(flushXpRunnable)
        val r = XpManager.flush() ?: return
        val v = kv
        v?.setPendingXp(0)
        if (v == null) return
        v.addFloatingXp(r.gained)
        if (soundOn) {
            val sfx = sounds ?: SoundFx(this).also { sounds = it }
            if (r.leveledUp) sfx.playLevelUp() else sfx.playOrb()
        }
        if (r.leveledUp) {
            v.showLevelUp(r.level, XpManager.titleFor(r.level), "")
            vibrate(60)
        }
        if (r.dailyBonus > 0) {
            v.showBanner("🔥 Racha de ${XpManager.streakDays} día" + (if (XpManager.streakDays > 1) "s" else ""), "Bono diario +${r.dailyBonus} XP")
        }
        for (a in r.achievements) v.showBanner("🏆 ${a.name}", "${a.desc} · +${a.xp} XP")
        refreshXp()
    }

    private fun afterWordCommitted(word: String, kind: WordKind) {
        if (!noLearn && predictionsOn) {
            dict.learnWord(word)
            prevWord?.let { p ->
                dict.learnBigram(p, word)
                prevWord2?.let { p2 -> dict.learnBigram("$p2 $p", word) }   // 3-word patterns
            }
        }
        prevWord2 = prevWord
        prevWord = word.lowercase()
        gainXp(word, kind)
    }

    /** Commit the composing word. Returns (typed, committed). */
    private fun commitWord(applyCorrection: Boolean): Pair<String, String> {
        val ic = ic() ?: return Pair("", "")
        val typed = composing.toString()
        var final = typed
        var kind = if (lastWordWasGlide) WordKind.GLIDE else WordKind.TYPED
        if (lastWordWasGlide) learnLastGlide(typed)
        if (applyCorrection && !lastWordWasGlide && correctionAllowed()) {
            val s = currentSuggestions()
            val ac = s?.autoCorrect
            if (ac != null) {
                final = matchCase(typed, ac)
                kind = WordKind.AUTOCORRECT
            }
        }
        ic.commitText(final, 1)
        if (kind != WordKind.GLIDE) GlideLog.typedAfterDelete(final)
        // kept exactly as typed with the keys: teach the touch model where you press
        if (final == typed && kind == WordKind.TYPED) {
            val v = kv
            val t = tapsFor(typed)
            if (v != null && t != null && v.unitW > 0f) {
                val raw = HashMap<Char, FloatArray>()
                for ((k, c) in v.rawLetterCenters()) raw[k] = floatArrayOf(c[0] / v.unitW, c[1] / v.keyRowH)
                TouchModel.learn(typed, t, raw)
            }
        }
        composing.setLength(0)
        lastWordWasGlide = false
        afterWordCommitted(final, kind)
        return Pair(typed, final)
    }

    private fun flushRawWord() {
        if (rawWordLen > 0 && !noLearn) gainXp("x".repeat(rawWordLen), WordKind.TYPED)
        rawWordLen = 0
    }

    // ================================================================= key handling

    override fun onPressFeedback(code: Int) {
        vibrate()
        keyClick(code)
    }

    override fun onKey(key: Key) {
        kv?.let { if (it.unitW > 0f) pendingTap = floatArrayOf(it.lastTapX / it.unitW, it.lastTapY / it.keyRowH) }
        when (key.code) {
            Codes.SHIFT -> handleShift()
            Codes.DELETE -> handleDelete()
            Codes.SYMBOLS -> { kv?.setLayout(LayoutType.SYMBOLS); lastCorrection = null }
            Codes.SYMBOLS2 -> { kv?.setLayout(LayoutType.SYMBOLS2); lastCorrection = null }
            Codes.LETTERS -> { kv?.setLayout(LayoutType.LETTERS); updateShiftAuto() }
            Codes.ENTER -> handleEnter()
            Codes.EMOJI -> { lastCorrection = null; kv?.showEmojis() }
            Codes.SPACE -> handleSpace()
            else -> handleText(String(Character.toChars(key.code)))
        }
    }

    override fun onAltText(text: String) {
        pendingTap = null
        handleText(text)
    }

    private fun handleShift() {
        val v = kv ?: return
        val now = SystemClock.uptimeMillis()
        v.shiftState = when (v.shiftState) {
            0 -> 1
            1 -> if (now - lastShiftTime < 350) 2 else 0
            else -> 0
        }
        lastShiftTime = now
        if (composing.isEmpty()) refreshSuggestions()
    }

    private fun handleText(s: String) {
        val ic = ic() ?: return
        val v = kv ?: return
        lastCorrection = null
        val isLetter = s.codePointCount(0, s.length) == 1 && Character.isLetter(s.codePointAt(0))
        startedTyping()
        if (isLetter && composing.isEmpty() && !directMode) resyncContext()

        if (directMode) {
            val out = if (isLetter && v.shiftState > 0) s.uppercase() else s
            ic.commitText(out, 1)
            if (isLetter) rawWordLen++ else flushRawWord()
            if (v.shiftState == 1) v.shiftState = 0
            return
        }

        if (s == "'" && composing.isNotEmpty() && !lastWordWasGlide && dict.lang != "es") {
            composing.append("'")
            ic.setComposingText(composing, 1)
            refreshSuggestions()
            return
        }

        if (isLetter) {
            if (lastWordWasGlide && composing.isNotEmpty()) {
                commitWord(false)
                ic.commitText(" ", 1)
            }
            lastWordWasGlide = false
            val c = if (v.shiftState > 0) s.uppercase() else s
            if (composing.isEmpty()) taps.clear()
            while (taps.size < composing.length) taps.add(null)
            composing.append(c)
            repeat(c.length) { taps.add(if (it == 0) pendingTap else null) }
            pendingTap = null
            ic.setComposingText(composing, 1)
            if (v.shiftState == 1) v.shiftState = 0
            autoSpace = false
            refreshSuggestions()
            return
        }

        val isPunct = s in setOf(".", ",", "!", "?", ";", ":")
        if (composing.isNotEmpty()) {
            commitWord(isPunct)
        } else if (autoSpace && isPunct) {
            val before = ic.getTextBeforeCursor(1, 0)?.toString()
            if (before == " ") ic.deleteSurroundingText(1, 0)
        }
        ic.commitText(s, 1)
        if (autoSpace && isPunct) {
            ic.commitText(" ", 1)
        } else {
            autoSpace = false
        }
        lastWordWasGlide = false
        if (s == "." || s == "!" || s == "?") { prevWord = null; prevWord2 = null }
        refreshSuggestions()
    }

    private fun handleSpace() {
        val ic = ic() ?: return
        val v = kv ?: return
        val now = SystemClock.uptimeMillis()
        startedTyping()
        if (directMode) {
            flushRawWord()
            ic.commitText(" ", 1)
            return
        }
        if (composing.isNotEmpty()) {
            val (typed, final) = commitWord(true)
            ic.commitText(" ", 1)
            lastCorrection = if (final != typed) Pair(typed, final) else null
            autoSpace = false
        } else {
            val before = ic.getTextBeforeCursor(2, 0)?.toString()
            val canPeriod = doubleSpaceOn && before != null && before.length == 2 &&
                before[1] == ' ' && before[0].isLetterOrDigit()
            if (canPeriod && (autoSpace || now - lastSpaceTime < 700)) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                prevWord = null
                prevWord2 = null
            } else {
                ic.commitText(" ", 1)
            }
            lastCorrection = null
            autoSpace = false
        }
        lastSpaceTime = now
        if (v.layout.type != LayoutType.LETTERS) v.setLayout(LayoutType.LETTERS)
        refreshSuggestions()
    }

    private fun handleDelete() {
        val ic = ic() ?: return
        val lc = lastCorrection
        lastCorrection = null
        if (lc != null && composing.isEmpty()) {
            // undo autocorrection, Gboard style
            val before = ic.getTextBeforeCursor(lc.second.length + 1, 0)?.toString()
            if (before == lc.second + " ") {
                ic.deleteSurroundingText(lc.second.length + 1, 0)
                dict.forceLearn(lc.first)
                composing.append(lc.first)
                ic.setComposingText(composing, 1)
                sugFor = null
                refreshSuggestions()
                return
            }
        }
        if (composing.isNotEmpty()) {
            if (lastWordWasGlide) {
                composing.setLength(0)
                GlideLog.deleted()
                lastGlideX = null; lastGlideY = null
            } else {
                val cp = Character.charCount(composing.codePointBefore(composing.length))
                composing.setLength(composing.length - cp)
                while (taps.size > composing.length) taps.removeAt(taps.size - 1)
            }
            lastWordWasGlide = false
            if (composing.isEmpty()) ic.commitText("", 1) else ic.setComposingText(composing, 1)
        } else {
            val sel = ic.getSelectedText(0)
            if (!sel.isNullOrEmpty()) ic.commitText("", 1)
            else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            if (directMode && rawWordLen > 0) rawWordLen--
        }
        autoSpace = false
        if (composing.isEmpty()) updateShiftAuto()
        refreshSuggestions()
    }

    override fun onDeleteRepeat() {
        lastCorrection = null
        handleDelete()
    }

    override fun onDeleteWords(count: Int) {
        val ic = ic() ?: return
        lastCorrection = null
        var n = count
        if (composing.isNotEmpty()) {
            composing.setLength(0)
            lastWordWasGlide = false
            ic.commitText("", 1)
            n--
        }
        for (i in 0 until n) {
            val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: break
            if (before.isEmpty()) break
            var j = before.length
            while (j > 0 && before[j - 1].isWhitespace()) j--
            while (j > 0 && !before[j - 1].isWhitespace()) j--
            ic.deleteSurroundingText(before.length - j, 0)
        }
        autoSpace = false
        vibrate(25)
        updateShiftAuto()
        refreshSuggestions()
    }

    private fun handleEnter() {
        val ic = ic() ?: return
        if (directMode) flushRawWord()
        if (composing.isNotEmpty()) commitWord(true)
        lastCorrection = null
        autoSpace = false
        prevWord = null
        prevWord2 = null
        lastWordWasGlide = false
        val ei = currentInputEditorInfo
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = ((ei?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendKeyChar('\n')
        }
        refreshSuggestions()
    }

    override fun onCursor(delta: Int) {
        val ic = ic() ?: return
        if (composing.isNotEmpty()) {
            ic.finishComposingText()
            composing.setLength(0)
            lastWordWasGlide = false
        }
        lastCorrection = null
        autoSpace = false
        if (delta > 0 && ic.getTextAfterCursor(1, 0).isNullOrEmpty()) return
        val pos = (selEnd + delta).coerceAtLeast(0)
        ic.setSelection(pos, pos)
        selStart = pos
        selEnd = pos
    }

    // ---- glide (swipe) typing: live preview while the finger moves, final word on release
    private var glidePrepared = false
    // last swipe (pixels) so it can be learned if you keep the word
    private var lastGlideX: FloatArray? = null
    private var lastGlideY: FloatArray? = null

    // Swipes are compared in key units measured from the "q" key (as on the standard keyboard), so
    // the swipes the keyboard learned keep matching with the number row, one-handed mode or another height.
    private val canonQX = 0.569f
    private val canonQY = 1.759f

    private fun canonPath(v: KeyboardView, px: FloatArray, py: FloatArray, n: Int): Pair<FloatArray, FloatArray> {
        val o = v.canonOrigin()
        val kw = v.unitW
        val kh = v.keyRowH
        return Pair(
            FloatArray(n) { (px[it] - o[0]) / kw + canonQX },
            FloatArray(n) { (py[it] - o[1]) / kh + canonQY }
        )
    }

    private fun canonCenters(v: KeyboardView): Map<Char, FloatArray> {
        val o = v.canonOrigin()
        val kw = v.unitW
        val kh = v.keyRowH
        val m = HashMap<Char, FloatArray>()
        for ((k, c) in v.letterCenters()) m[k] = floatArrayOf((c[0] - o[0]) / kw + canonQX, (c[1] - o[1]) / kh + canonQY)
        return m
    }

    private fun learnLastGlide(word: String) {
        val x = lastGlideX ?: return
        val y = lastGlideY ?: return
        val v = kv ?: return
        lastGlideX = null; lastGlideY = null
        GlideLog.kept(word)
        if (noLearn || v.unitW <= 0f || v.keyRowH <= 0f) return
        val (cx, cy) = canonPath(v, x, y, x.size)
        dict.learnGlide(word, cx, cy, cx.size, 1f, 1f)
        // also learn where your finger passes for each key while swiping
        if (v.unitW > 0f && v.keyRowH > 0f) {
            val raw = HashMap<Char, FloatArray>()
            for ((k, c) in v.rawLetterCenters()) raw[k] = floatArrayOf(c[0] / v.unitW, c[1] / v.keyRowH)
            TouchModel.learnGlide(word, FloatArray(x.size) { x[it] / v.unitW }, FloatArray(y.size) { y[it] / v.keyRowH }, raw)
        }
    }
    private var glideSeq = 0

    /** Commit whatever was being typed and put a space before the new glided word. */
    private fun prepareGlide() {
        val ic = ic() ?: return
        lastCorrection = null
        startedTyping()
        if (composing.isNotEmpty()) commitWord(!lastWordWasGlide)
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "¿¡(\"'«“@#/") ic.commitText(" ", 1)
        glidePrepared = true
    }

    override fun onGlideStart() {
        if (!dict.loaded) return
        prepareGlide()
    }

    private fun showGlideWord(res: List<String>, final: Boolean) {
        val ic = ic() ?: return
        val v = kv ?: return
        val cased = res.map { applyShiftToWord(it) }
        v.setStrip(false, null)
        composing.setLength(0)
        composing.append(cased[0])
        ic.setComposingText(composing, 1)
        lastWordWasGlide = true
        autoSpace = false
        slots = listOf(cased.getOrElse(1) { "" }, cased[0], cased.getOrElse(2) { "" })
        v.setSuggestions(slots, 1)
        if (final) {
            if (v.shiftState == 1) v.shiftState = 0
        } else {
            v.setGlidePreview(cased[0])
        }
    }

    override fun onGlideProgress(px: FloatArray, py: FloatArray, n: Int) {
        val v = kv ?: return
        if (!dict.loaded || !glidePrepared) return
        if (v.unitW <= 0f || v.keyRowH <= 0f) return
        val seq = ++glideSeq
        val centers = canonCenters(v)
        val (cx, cy) = canonPath(v, px, py, n)
        val prev = context()
        worker.removeCallbacksAndMessages(null)
        worker.post {
            val res = try { dict.glide(cx, cy, n, centers, 1f, 1f, prev, live = true) } catch (_: Exception) { emptyList() }
            main.post {
                CrashLog.safe("deslizar") {
                    if (seq == glideSeq && glidePrepared && res.isNotEmpty()) showGlideWord(res, false)
                }
            }
        }
    }

    override fun onGlide(px: FloatArray, py: FloatArray, n: Int) {
        val v = kv ?: return
        if (!dict.loaded) return
        glideSeq++                       // drop pending live results
        worker.removeCallbacksAndMessages(null)
        if (!glidePrepared) prepareGlide()
        glidePrepared = false
        lastGlideX = px.copyOf(n)
        lastGlideY = py.copyOf(n)
        if (v.unitW <= 0f || v.keyRowH <= 0f) return
        val (cx, cy) = canonPath(v, px, py, n)
        val centers = canonCenters(v)
        val res = dict.glide(cx, cy, n, centers, 1f, 1f, context())
        GlideLog.setCenters(centers)
        val target = Trainer.target
        if (target != null) {
            // trainer: we know the word you meant, so it's recorded and learned right away
            GlideLog.trainer(target, cx, cy, n, res, context())
            if (!noLearn) dict.learnGlide(target, cx, cy, n, 1f, 1f)
            lastGlideX = null; lastGlideY = null
            main.post { CrashLog.safe("entrenador") { Trainer.onResult?.invoke(res) } }
        } else {
            GlideLog.swiped(cx, cy, n, res, context())
        }
        if (res.isEmpty()) {
            // keep the live preview if there was one
            if (composing.isNotEmpty() && lastWordWasGlide) {
                kv?.let { if (it.shiftState == 1) it.shiftState = 0 }
            } else refreshSuggestions()
            return
        }
        showGlideWord(res, true)
    }

    override fun onSuggestion(index: Int) {
        val ic = ic() ?: return
        val word = slots.getOrNull(index) ?: return
        if (word.isEmpty()) return
        lastCorrection = null
        startedTyping()
        if (word.none { it.isLetterOrDigit() }) {
            // emoji suggestion: replaces the word you were typing
            val typed = composing.toString()
            ic.commitText(word, 1)
            composing.setLength(0)
            lastWordWasGlide = false
            ic.commitText(" ", 1)
            if (typed.isNotEmpty()) gainXp(typed, WordKind.SUGGESTION)
            kv?.addRecentEmoji(word)
            autoSpace = true
            lastSpaceTime = SystemClock.uptimeMillis()
            kv?.let { if (it.shiftState == 1) it.shiftState = 0 }
            refreshSuggestions()
            return
        }
        if (composing.isNotEmpty()) {
            val typed = composing.toString()
            if (lastWordWasGlide) learnLastGlide(word)
            val kind = when {
                lastWordWasGlide -> WordKind.GLIDE
                word == typed -> WordKind.TYPED
                else -> WordKind.SUGGESTION
            }
            if (word == typed && !lastWordWasGlide) dict.forceLearn(word)
            ic.commitText(word, 1)
            composing.setLength(0)
            lastWordWasGlide = false
            afterWordCommitted(word, kind)
        } else {
            val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
            if (before.isNotEmpty() && !before[0].isWhitespace()) ic.commitText(" ", 1)
            ic.commitText(word, 1)
            afterWordCommitted(word, WordKind.SUGGESTION)
        }
        ic.commitText(" ", 1)
        autoSpace = true
        lastSpaceTime = SystemClock.uptimeMillis()
        kv?.let { if (it.shiftState == 1) it.shiftState = 0 }
        refreshSuggestions()
    }

    // ================================================================= clipboard

    private var clipboard: ClipboardManager? = null
    private val clipHistory = ArrayList<String>()
    /** Pinned clips: shown first, never trimmed nor cleared with "Borrar todo". */
    private val clipPinned = LinkedHashSet<String>()
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        CrashLog.safe("portapapeles") {
            captureClip(justCopied = true)
            if (composing.isEmpty()) refreshSuggestions()
        }
    }
    private val clipSep = "\u001E"

    private fun loadClips() {
        val raw = settings.getString("clips", "") ?: ""
        clipHistory.clear()
        if (raw.isNotEmpty()) clipHistory.addAll(raw.split(clipSep).filter { it.isNotEmpty() })
        val pins = settings.getString("clips_pinned", "") ?: ""
        clipPinned.clear()
        if (pins.isNotEmpty()) clipPinned.addAll(pins.split(clipSep).filter { it.isNotEmpty() && it in clipHistory })
    }

    /** What the clipboard panel shows: pinned first, then the rest from newest to oldest. */
    private fun clipList(): List<String> =
        clipHistory.filter { it in clipPinned } + clipHistory.filter { it !in clipPinned }

    private fun trimClips() {
        var i = clipHistory.size - 1
        while (clipHistory.size - clipPinned.size > 25 && i >= 0) {
            if (clipHistory[i] !in clipPinned) clipHistory.removeAt(i)
            i--
        }
    }

    private fun saveClips() {
        settings.edit()
            .putString("clips", clipHistory.joinToString(clipSep))
            .putString("clips_pinned", clipPinned.joinToString(clipSep))
            .apply()
        kv?.setClips(clipList(), HashSet(clipPinned))
    }

    /**
     * Reads what's copied right now and adds it to the history (skips passwords marked sensitive).
     * If it was copied a moment ago it's also offered as a chip to paste it with one tap.
     */
    private fun captureClip(justCopied: Boolean = false) {
        try {
            val clip = clipboard?.primaryClip ?: return
            if (clip.itemCount == 0) return
            val desc = clip.description
            val sensitive = desc?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
            if (sensitive) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            if (text.isBlank() || text.length > 5000) return
            if (clipHistory.firstOrNull() != text) {
                clipHistory.remove(text)
                clipHistory.add(0, text)
                trimClips()
                saveClips()
            }
            armChip(text, if (justCopied) System.currentTimeMillis() else (desc?.timestamp ?: 0L))
        } catch (_: Exception) {
        }
    }

    override fun onClipboard() {
        captureClip()
        kv?.setClips(clipList(), HashSet(clipPinned))
        kv?.showClipboard()
    }

    override fun onClipPick(index: Int) {
        val ic = ic() ?: return
        val text = clipList().getOrNull(index) ?: return
        if (composing.isNotEmpty()) commitWord(false)
        ic.commitText(text, 1)
        typedSinceStart = true
        if (chipText == text) consumeChip()
        lastCorrection = null
        autoSpace = false
        kv?.setLayout(LayoutType.LETTERS)
        updateShiftAuto()
        refreshSuggestions()
    }

    override fun onSuggestionRemove(word: String) {
        dict.blockWord(word)
        sugFor = null
        sugResult = null
        kv?.showBanner("🚫 «$word» ya no se sugerirá", "Puedes recuperarla en la app, en Mis palabras")
        if (composing.isNotEmpty() && lastWordWasGlide) {
            // swiped word: just take it out of the alternatives
            slots = slots.map { if (it.equals(word, ignoreCase = true)) "" else it }
            kv?.setSuggestions(slots, 1)
        } else {
            refreshSuggestions()
        }
    }

    override fun onClipDelete(index: Int) {
        val text = clipList().getOrNull(index) ?: return
        clipHistory.remove(text)
        clipPinned.remove(text)
        saveClips()
    }

    override fun onClipPin(index: Int) {
        val text = clipList().getOrNull(index) ?: return
        if (!clipPinned.remove(text)) {
            clipPinned.add(text)
            kv?.showBanner("📌 Fijado", "Se queda en tu portapapeles hasta que lo quites")
        }
        saveClips()
    }

    override fun onClipClear() {
        clipHistory.retainAll { it in clipPinned }
        saveClips()
    }

    // ================================================================= text editing panel

    override fun onEditAction(action: Int, selecting: Boolean) {
        val ic = ic() ?: return
        if (action == KeyboardView.E_DEL) {
            handleDelete()
            return
        }
        if (composing.isNotEmpty()) {
            ic.finishComposingText()
            composing.setLength(0)
            lastWordWasGlide = false
        }
        lastCorrection = null
        autoSpace = false
        val shift = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        when (action) {
            KeyboardView.E_LEFT -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT, shift)
            KeyboardView.E_RIGHT -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, shift)
            KeyboardView.E_UP -> sendKey(KeyEvent.KEYCODE_DPAD_UP, shift)
            KeyboardView.E_DOWN -> sendKey(KeyEvent.KEYCODE_DPAD_DOWN, shift)
            KeyboardView.E_HOME -> sendKey(KeyEvent.KEYCODE_MOVE_HOME, shift)
            KeyboardView.E_END -> sendKey(KeyEvent.KEYCODE_MOVE_END, shift)
            KeyboardView.E_SELALL -> ic.performContextMenuAction(android.R.id.selectAll)
            KeyboardView.E_COPY, KeyboardView.E_CUT -> {
                if (ic.getSelectedText(0).isNullOrEmpty()) {
                    Toast.makeText(this, "Primero selecciona el texto", Toast.LENGTH_SHORT).show()
                } else {
                    ic.performContextMenuAction(if (action == KeyboardView.E_COPY) android.R.id.copy else android.R.id.cut)
                }
            }
            KeyboardView.E_PASTE -> {
                ic.performContextMenuAction(android.R.id.paste)
                typedSinceStart = true
                consumeChip()
            }
            KeyboardView.E_UNDO -> sendKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        }
    }

    /** Sends a key like a physical keyboard would (with Shift / Ctrl held when [meta] says so). */
    private fun sendKey(code: Int, meta: Int = 0) {
        val ic = ic() ?: return
        val t = SystemClock.uptimeMillis()
        fun ev(action: Int, c: Int, m: Int) = KeyEvent(
            t, t, action, c, 0, m, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        )
        val ctrl = meta and KeyEvent.META_CTRL_ON != 0
        val shift = meta and KeyEvent.META_SHIFT_ON != 0
        if (ctrl) ic.sendKeyEvent(ev(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
        if (shift) ic.sendKeyEvent(ev(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
        ic.sendKeyEvent(ev(KeyEvent.ACTION_DOWN, code, meta))
        ic.sendKeyEvent(ev(KeyEvent.ACTION_UP, code, meta))
        if (shift) ic.sendKeyEvent(ev(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0))
        if (ctrl) ic.sendKeyEvent(ev(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0))
    }

    override fun onOneHand(mode: Int) {
        val e = settings.edit().putInt("onehand", mode)
        if (mode > 0) e.putInt("onehand_side", mode)
        e.apply()
    }

    override fun onSettings() = onBadge()

    override fun onBadge() {
        try {
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(i)
            requestHideSelf(0)
        } catch (_: Exception) {
        }
    }

    // ================================================================= voice typing

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /** Keeps listening until this long passes without you saying anything. */
    private val voiceSilenceMs: Long get() = settings.getInt("voicesilence", 5000).toLong()
    private var voiceUserStop = false
    private var lastSpeechAt = 0L
    private var voiceIntent: Intent? = null

    override fun onMic() {
        if (listening) {
            voiceUserStop = true
            recognizer?.stopListening()   // final results still arrive
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            try {
                startActivity(Intent(this, PermisoMicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Tu celular no tiene reconocimiento de voz (instala la app de Google)", Toast.LENGTH_LONG).show()
            return
        }
        val ic = ic() ?: return
        val v = kv ?: return
        lastCorrection = null
        if (composing.isNotEmpty()) commitWord(!lastWordWasGlide)
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(voiceListener)
            recognizer = it
        }
        val lang = Locale.getDefault().let { if (it.language == "es") it.toLanguageTag() else "es-419" }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, voiceSilenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, voiceSilenceMs)
        }
        voiceIntent = intent
        voiceUserStop = false
        lastSpeechAt = SystemClock.uptimeMillis()
        voiceCaps = v.shiftState > 0
        voiceNeedsSpace = (ic.getTextBeforeCursor(1, 0)?.toString() ?: "").let { it.isNotEmpty() && !it[0].isWhitespace() }
        listening = true
        v.setVoice(true)
        vibrate(20)
        try {
            r.startListening(intent)
        } catch (_: Exception) {
            listening = false
            v.setVoice(false)
        }
    }

    private var voiceCaps = false
    private var voiceNeedsSpace = false

    private fun formatVoice(text: String): String {
        var t = text.trim()
        if (t.isEmpty()) return t
        if (voiceCaps) t = t.replaceFirstChar { it.uppercaseChar() }
        if (voiceNeedsSpace) t = " $t"
        return t
    }

    private fun restartVoice() {
        val r = recognizer ?: return
        val i = voiceIntent ?: return
        // next sentence continues after a space
        voiceCaps = false
        voiceNeedsSpace = (ic()?.getTextBeforeCursor(1, 0)?.toString() ?: "").let { it.isNotEmpty() && !it[0].isWhitespace() }
        listening = true
        kv?.setVoice(true)
        main.postDelayed({
            if (listening && !voiceUserStop) {
                try { r.startListening(i) } catch (_: Exception) { listening = false; kv?.setVoice(false) }
            }
        }, 80)
    }

    private fun cancelVoice() {
        if (!listening) return
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        if (composing.isNotEmpty()) {
            ic()?.commitText("", 1)
            composing.setLength(0)
        }
        kv?.setVoice(false)
    }

    private val voiceListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { lastSpeechAt = SystemClock.uptimeMillis() }
        override fun onRmsChanged(rmsdB: Float) { kv?.setVoiceLevel(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            CrashLog.safe("voz") {
                if (!listening) return
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                if (text.isBlank()) return
                lastSpeechAt = SystemClock.uptimeMillis()
                val ic = ic() ?: return
                composing.setLength(0)
                composing.append(formatVoice(text))
                ic.setComposingText(composing, 1)
                kv?.setVoice(true, text)
            }
        }

        override fun onResults(results: Bundle?) {
            CrashLog.safe("voz") {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                val keepGoing = !voiceUserStop && SystemClock.uptimeMillis() - lastSpeechAt < voiceSilenceMs
                listening = keepGoing
                if (!keepGoing) kv?.setVoice(false)
                val ic = ic() ?: return
                val out = formatVoice(text)
                if (out.isBlank()) {
                    if (composing.isNotEmpty()) ic.commitText("", 1)
                    composing.setLength(0)
                    if (keepGoing) restartVoice()
                    return
                }
                ic.commitText(out, 1)
                composing.setLength(0)
                lastWordWasGlide = false
                val words = text.trim().split(Regex("\\s+")).filter { w -> w.any { it.isLetter() } }
                for (w in words) afterWordCommitted(w.trim { !it.isLetterOrDigit() }, WordKind.TYPED)
                kv?.let { if (it.shiftState == 1) it.shiftState = 0 }
                refreshSuggestions()
                if (keepGoing) restartVoice()
            }
        }

        override fun onError(error: Int) {
            CrashLog.safe("voz") {
                // silence / no match: keep listening until 5 s have passed without speech
                if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
                    !voiceUserStop && listening && SystemClock.uptimeMillis() - lastSpeechAt < voiceSilenceMs
                ) {
                    if (composing.isNotEmpty()) { ic()?.finishComposingText(); composing.setLength(0) }
                    restartVoice()
                    return
                }
                val hadText = composing.isNotEmpty()
                listening = false
                kv?.setVoice(false)
                if (hadText) {
                    // keep what was already heard
                    ic()?.finishComposingText()
                    composing.setLength(0)
                }
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (hadText) null else "No te escuché, intenta otra vez"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sin internet para el dictado"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta el permiso del micrófono"
                    SpeechRecognizer.ERROR_CLIENT -> null
                    else -> "No se pudo usar el micrófono (error $error)"
                }
                if (msg != null) Toast.makeText(this@XpKeyboardService, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Long-press on space: switch language (Español → English → Español · English). */
    override fun onSpaceLongPress() {
        vibrate(30)
        val next = when (langSetting()) { "es" -> "en"; "en" -> "es_en"; else -> "es" }
        settings.edit().putString("lang", next).apply()
        kv?.spaceLabel = langLabel(next)
        kv?.showBanner("🌐 ${langLabel(next)}", "Mantén la barra espaciadora para cambiar de idioma")
        ensureLanguage()
    }
}
