package com.kydium.tecladoxp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onKey(key: Key)
        fun onAltText(text: String)
        fun onGlide(px: FloatArray, py: FloatArray, n: Int)
        fun onGlideStart()
        fun onGlideProgress(px: FloatArray, py: FloatArray, n: Int)
        fun onCursor(delta: Int)
        fun onDeleteRepeat()
        fun onDeleteWords(count: Int)
        fun onSuggestion(index: Int)
        fun onBadge()
        fun onSpaceLongPress()
        fun onMic()
        fun onClipboard()
        fun onSettings()
        fun onClipPick(index: Int)
        fun onClipDelete(index: Int)
        /** Pin / unpin a clipboard item (pinned ones stay until you remove them). */
        fun onClipPin(index: Int)
        fun onClipClear()
        /** Paste the text you just copied (the chip in the suggestion bar). */
        fun onClipChip()
        /** Text editing panel: arrows, select, copy, paste… ([selecting] = extend the selection). */
        fun onEditAction(action: Int, selecting: Boolean)
        /** One-handed mode changed: 0 off, 1 left, 2 right. */
        fun onOneHand(mode: Int)
        /** Vibration / click when a key is pressed ([code] = key code, 0 for other buttons). */
        fun onPressFeedback(code: Int)
        /** Long-press on a suggestion and confirm: don't suggest [word] anymore. */
        fun onSuggestionRemove(word: String)
    }

    companion object {
        const val E_LEFT = 1
        const val E_RIGHT = 2
        const val E_UP = 3
        const val E_DOWN = 4
        const val E_HOME = 5
        const val E_END = 6
        const val E_SELECT = 7
        const val E_SELALL = 8
        const val E_COPY = 9
        const val E_CUT = 10
        const val E_PASTE = 11
        const val E_UNDO = 12
        const val E_DEL = 13
        const val E_BACK = 14
    }

    // ------------------------------------------------------------------ config
    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)

    var theme: KbTheme = Themes.all[0]
        set(v) { field = v; bgShader = null; invalidate() }
    var heightScale = 1f
        set(v) { if (field != v) { field = v; requestLayout() } }
    var showHints = true
    var glideEnabled = true
    var showFloatingXp = true
    /** Space between keys (only how they look; the touch area of each key stays the same): 0 tight, 1 like Gboard, 2 wide. */
    var keySpacing = 1
        set(v) { if (field != v) { field = v; invalidate() } }
    private fun keyGapX() = when (keySpacing) { 0 -> 3f; 2 -> 9f; else -> 6f }
    private fun keyGapY() = when (keySpacing) { 0 -> 4.5f; 2 -> 14f; else -> 10f }

    /** Bubble above the key while you press it. */
    var showPreview = true
    var longPressMs = 320L
    var xpStyle = "clasica"
        set(v) { field = v; invalidate() }
    var trailStyle = "clasica"
    val renderer = StyleRenderer(resources.displayMetrics)

    private var letters = Layouts.letters()
    private val symbols = Layouts.symbols()
    private val symbols2 = Layouts.symbols2()
    var layout: KbLayout = letters
        private set

    /** Row of numbers above the letters. */
    var numberRow = false
        set(v) {
            if (field == v) return
            field = v
            val wasLetters = layout === letters
            letters = Layouts.letters(v)
            if (wasLetters) layout = letters
            if (width > 0) relayout(width, height)
            requestLayout()
            invalidate()
        }

    /** One-handed mode: 0 off, 1 keys on the left, 2 keys on the right. */
    var oneHand = 0
        set(v) {
            if (field == v) return
            field = v
            if (width > 0) relayout(width, height)
            invalidate()
        }
    /** Side used when you turn one-handed mode on from the toolbar. */
    var oneHandSide = 2
    private val oneHandFrac = 0.80f
    private fun kbWidth(w: Float) = if (oneHand > 0) w * oneHandFrac else w
    private fun kbLeft(w: Float) = if (oneHand == 2) w - kbWidth(w) else 0f

    /** 0 = off, 1 = shift once, 2 = caps lock */
    var shiftState = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var enterLabel = "↵"
        set(v) { field = v; invalidate() }
    var spaceLabel = "Español"

    private var suggestions: List<String> = listOf("", "", "")
    private var boldIndex = -1

    // xp state
    private var level = 1
    private var xpFrac = 0f
    private var xpLine = ""
    private var combo = 0
    private var streak = 0
    private var pendingXp = 0L

    /** XP earned while typing that hasn't been added to the bar yet. */
    fun setPendingXp(v: Long) {
        pendingXp = v
        invalidate()
    }
    private var comboExpire = 0L
    // xp gain animation: the earned part turns white, then fills with color
    private var animFrom = 0f
    private var animTo = 0f
    private var animStart = 0L
    private val animWhiteMs = 280L
    private val animFillMs = 650L

    // ------------------------------------------------------------------ geometry
    private val topH get() = dp(64f)
    private val sugH get() = dp(32f)
    private val rowH get() = dp(54f) * heightScale
    private val padY get() = dp(4f)
    private val padX get() = dp(3f)
    var unitW = 1f
        private set
    val keyRowH: Float get() = rowH

    // ------------------------------------------------------------------ paints
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bgPaint = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bgShader: Shader? = null

    /** Picture behind the keys (your own theme), or null for the theme's colors. */
    var bgImage: android.graphics.Bitmap? = null
        set(v) { field = v; invalidate() }
    /** Where the picture sits: x, y (0..1) and zoom. */
    var bgPos = floatArrayOf(0.5f, 0.5f, 1f)

    /** Keys with depth: a thicker edge below and light on top. */
    var keys3d = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val srcRect = android.graphics.Rect()
    private val dstRect = RectF()
    private val rect = RectF()

    // ------------------------------------------------------------------ touch state
    private enum class Mode { NONE, PRESS, POPUP, GLIDE, CURSOR, DELSWIPE, STRIP, EMOJI, CLIP, EDIT }

    private var mode = Mode.NONE
    private var activeId = -1
    private var downKey: Key? = null
    private var curKey: Key? = null
    private var downX = 0f
    private var downY = 0f
    private var lastCursorX = 0f
    private var delSteps = 0
    private var delRepeated = false
    private var stripPressed = -1

    private var popupAlts: List<String> = emptyList()
    private var popupLeft = 0f
    private var popupTop = 0f
    private var popupCellW = 0f
    private var popupH = 0f
    private var popupSel = 0

    private var gx = FloatArray(1024)
    private var gy = FloatArray(1024)
    private var gCount = 0
    private var trailFadeStart = 0L
    /** Where the finger touched for the last key tap (used by the autocorrector). */
    var lastTapX = 0f
        private set
    var lastTapY = 0f
        private set
    private var lastGlideProgress = 0L
    private var glidePreview = ""

    /** Word currently recognized while the finger is still gliding (shown in a bubble). */
    fun setGlidePreview(word: String) {
        glidePreview = word
        invalidate()
    }

    // ------------------------------------------------------------------ animations
    private class FloatText(val text: String, val x: Float, val y: Float, val start: Long, val color: Int)
    private class Particle(var x: Float, var y: Float, val vx: Float, var vy: Float, val color: Int, val size: Float, val rot: Float)
    private class Banner(val title: String, val subtitle: String, val big: Boolean)

    private val floats = ArrayList<FloatText>()
    private val particles = ArrayList<Particle>()
    private var particlesStart = 0L
    private val banners = ArrayList<Banner>()
    private var bannerStart = 0L
    private val bannerDuration = 2600L

    // ================================================================== public API

    fun setLayout(type: LayoutType) {
        removeAsk = null
        emojiMode = false
        voiceMode = false
        clipMode = false
        editMode = false
        layout = when (type) {
            LayoutType.LETTERS -> letters
            LayoutType.SYMBOLS -> symbols
            LayoutType.SYMBOLS2 -> symbols2
        }
        cancelGesture()
        invalidate()
    }

    // ------------------------------------------------------------------ emoji panel
    var emojiMode = false
        private set
    private var emojiCats: List<Pair<String, List<String>>> = emptyList()   // (tab icon, emojis); 0 = recents
    private val recents = ArrayList<String>()
    private var emojiCat = 1
    private var emojiScroll = 0f
    private var emojiVel = 0f
    private var emojiScrolled = false
    private var lastMoveY = 0f
    private var lastMoveT = 0L
    private val emojiBar = listOf(
        Key("ABC", Codes.LETTERS, 1.6f),
        Key("", Codes.SPACE, 6.8f),
        Key("⌫", Codes.DELETE, 1.6f)
    )
    private val emojiCols = 8
    private val emojiTabH get() = dp(40f)
    private val emojiCellH get() = dp(46f)
    private val emojiGridTop get() = topH + padY + emojiTabH
    private val emojiGridBottom get() = emojiBar[0].top - dp(2f)

    fun showEmojis() {
        if (emojiCats.isEmpty()) loadEmojis()
        emojiMode = true
        clipMode = false
        editMode = false
        emojiCat = if (recents.isNotEmpty()) 0 else 1
        emojiScroll = 0f
        emojiVel = 0f
        cancelGesture()
        invalidate()
    }

    private fun loadEmojis() {
        val cats = ArrayList<Pair<String, List<String>>>()
        cats.add(Pair("🕘", recents))
        try {
            context.assets.open("emojis.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                val probe = Paint()
                lines.forEach { line ->
                    val parts = line.split('\t')
                    if (parts.size > 1) {
                        val list = ArrayList<String>()
                        for (tok in parts.drop(1)) {
                            if (tok.isEmpty()) continue
                            // "base|tone1|tone2…"; "~" marks recent emojis (Emoji 15+): those are
                            // only shown if this phone's font can draw them
                            val isNew = tok.startsWith("~")
                            val all = tok.removePrefix("~").split('|')
                            val vars = if (isNew) all.filter { probe.hasGlyph(it) } else all
                            if (vars.isEmpty()) continue
                            list.add(vars[0])
                            if (vars.size > 1) emojiVariants[vars[0]] = vars
                        }
                        cats.add(Pair(parts[0], list))
                    }
                }
            }
        } catch (_: Exception) {
        }
        val saved = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("recent_emojis", "") ?: ""
        recents.clear()
        recents.addAll(saved.split('\t').filter { it.isNotEmpty() })
        emojiCats = cats
    }

    /** Skin-tone variants for emojis that have them (base -> [base, tones…]). */
    private val emojiVariants = HashMap<String, List<String>>()

    /** Long-press on an emoji with skin tones opens a popup to pick one. */
    private val emojiLongPress = Runnable {
        if (mode != Mode.EMOJI || emojiScrolled) return@Runnable
        val e = emojiAt(downX, downY) ?: return@Runnable
        val vars = emojiVariants[e] ?: return@Runnable
        val cellW = width / emojiCols.toFloat()
        val col = (downX / cellW).toInt().coerceIn(0, emojiCols - 1)
        val row = ((downY - emojiGridTop + emojiScroll) / emojiCellH).toInt()
        val k = Key(e, -100, 1f, vars)
        k.left = col * cellW
        k.right = k.left + cellW
        k.top = emojiGridTop + row * emojiCellH - emojiScroll
        k.bottom = k.top + emojiCellH
        openPopup(k)
        listener.onPressFeedback(0)
        invalidate()
    }

    private fun addRecent(e: String) {
        recents.remove(e)
        recents.add(0, e)
        while (recents.size > 32) recents.removeAt(recents.size - 1)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("recent_emojis", recents.joinToString("\t")).apply()
    }

    private fun emojiMaxScroll(): Float {
        val list = emojiCats.getOrNull(emojiCat)?.second ?: return 0f
        val rows = (list.size + emojiCols - 1) / emojiCols
        return max(0f, rows * emojiCellH - (emojiGridBottom - emojiGridTop))
    }

    private fun emojiAt(x: Float, y: Float): String? {
        if (y < emojiGridTop || y > emojiGridBottom) return null
        val list = emojiCats.getOrNull(emojiCat)?.second ?: return null
        val cellW = width / emojiCols.toFloat()
        val col = (x / cellW).toInt().coerceIn(0, emojiCols - 1)
        val row = ((y - emojiGridTop + emojiScroll) / emojiCellH).toInt()
        return list.getOrNull(row * emojiCols + col)
    }

    private fun drawEmojiPanel(c: Canvas) {
        val t = theme
        val w = width.toFloat()
        // tabs
        val tabs = emojiCats.size
        if (tabs > 0) {
            val tw = w / tabs
            val ty = topH + padY
            emojiCats.forEachIndexed { i, cat ->
                val cx = tw * i + tw / 2f
                textPaint.typeface = Typeface.DEFAULT
                textPaint.textSize = sp(18f)
                textPaint.color = t.text
                val sel = i == emojiCat
                if (sel) {
                    keyPaint.shader = null
                    keyPaint.color = t.keyPressed
                    rect.set(cx - tw / 2f + dp(2f), ty + dp(2f), cx + tw / 2f - dp(2f), ty + emojiTabH - dp(4f))
                    c.drawRoundRect(rect, dp(10f), dp(10f), keyPaint)
                    keyPaint.color = t.accent
                    c.drawRect(cx - dp(10f), ty + emojiTabH - dp(4f), cx + dp(10f), ty + emojiTabH - dp(2f), keyPaint)
                }
                c.drawText(cat.first, cx, ty + emojiTabH / 2f - (textPaint.ascent() + textPaint.descent()) / 2f - dp(1f), textPaint)
            }
        }
        // grid
        val list = emojiCats.getOrNull(emojiCat)?.second ?: emptyList()
        val top = emojiGridTop
        val bottom = emojiGridBottom
        c.save()
        c.clipRect(0f, top, w, bottom)
        if (list.isEmpty()) {
            textPaint.textSize = sp(13f)
            textPaint.color = t.textMuted
            c.drawText("Aquí aparecerán tus emojis recientes", w / 2f, (top + bottom) / 2f, textPaint)
        } else {
            val cellW = w / emojiCols
            val firstRow = (emojiScroll / emojiCellH).toInt()
            val lastRow = ((emojiScroll + (bottom - top)) / emojiCellH).toInt() + 1
            textPaint.textSize = sp(26f)
            textPaint.color = t.text
            val off = -(textPaint.ascent() + textPaint.descent()) / 2f
            for (r in firstRow..lastRow) for (col in 0 until emojiCols) {
                val e = list.getOrNull(r * emojiCols + col) ?: continue
                val cy = top + r * emojiCellH - emojiScroll + emojiCellH / 2f
                c.drawText(e, col * cellW + cellW / 2f, cy + off, textPaint)
            }
        }
        c.restore()
        // fling
        if (mode != Mode.EMOJI && abs(emojiVel) > 0.05f) {
            emojiScroll = (emojiScroll + emojiVel * 16f).coerceIn(0f, emojiMaxScroll())
            emojiVel *= 0.93f
            postInvalidateOnAnimation()
        }
    }

    // ------------------------------------------------------------------ clipboard
    var clipMode = false
        private set
    private var clips: List<String> = emptyList()
    private var clipPinned: Set<String> = emptySet()
    private var clipScroll = 0f
    private var clipScrolled = false
    private val clipHeaderH get() = dp(42f)
    private val clipItemH get() = dp(54f)

    fun setClips(list: List<String>, pinned: Set<String> = emptySet()) {
        clips = list
        clipPinned = pinned
        clipScroll = clipScroll.coerceIn(0f, clipMaxScroll())
        invalidate()
    }

    private fun drawPinIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, filled: Boolean) {
        linePaint.shader = null
        linePaint.color = color
        linePaint.strokeWidth = dp(1.6f)
        linePaint.strokeCap = Paint.Cap.ROUND
        c.save()
        c.rotate(35f, cx, cy)
        // head
        linePaint.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        rect.set(cx - s * 0.45f, cy - s, cx + s * 0.45f, cy - s * 0.15f)
        c.drawRoundRect(rect, s * 0.15f, s * 0.15f, linePaint)
        linePaint.style = Paint.Style.STROKE
        c.drawLine(cx - s * 0.75f, cy - s * 0.1f, cx + s * 0.75f, cy - s * 0.1f, linePaint)
        // needle
        c.drawLine(cx, cy - s * 0.1f, cx, cy + s, linePaint)
        c.restore()
        linePaint.style = Paint.Style.FILL
    }

    fun showClipboard() {
        emojiMode = false
        editMode = false
        clipMode = true
        clipScroll = 0f
        cancelGesture()
        invalidate()
    }

    private fun clipMaxScroll(): Float =
        max(0f, clips.size * clipItemH + dp(8f) - (height - topH - clipHeaderH))

    private fun drawClipIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        linePaint.shader = null
        linePaint.color = color
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = dp(1.7f)
        rect.set(cx - s * 0.8f, cy - s * 0.85f, cx + s * 0.8f, cy + s * 1.05f)
        c.drawRoundRect(rect, s * 0.25f, s * 0.25f, linePaint)
        linePaint.style = Paint.Style.FILL
        rect.set(cx - s * 0.4f, cy - s * 1.15f, cx + s * 0.4f, cy - s * 0.6f)
        c.drawRoundRect(rect, s * 0.15f, s * 0.15f, linePaint)
        linePaint.strokeWidth = dp(1.5f)
        c.drawRect(cx - s * 0.45f, cy - s * 0.15f, cx + s * 0.45f, cy - s * 0.15f + dp(1.5f), linePaint)
        c.drawRect(cx - s * 0.45f, cy + s * 0.35f, cx + s * 0.25f, cy + s * 0.35f + dp(1.5f), linePaint)
    }

    private fun drawGearIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        linePaint.shader = null
        linePaint.color = color
        linePaint.style = Paint.Style.FILL
        // teeth
        for (i in 0 until 8) {
            c.save()
            c.rotate(i * 45f, cx, cy)
            c.drawRect(cx - s * 0.2f, cy - s * 1.05f, cx + s * 0.2f, cy - s * 0.6f, linePaint)
            c.restore()
        }
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = s * 0.35f
        c.drawCircle(cx, cy, s * 0.6f, linePaint)
        linePaint.style = Paint.Style.FILL
    }

    private fun drawClipPanel(c: Canvas, t: KbTheme) {
        val w = width.toFloat()
        val top = topH
        // header: back to letters · title · clear all
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = sp(14f)
        textPaint.color = t.text
        c.drawText("ABC", dp(32f), top + clipHeaderH / 2f - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        c.drawText("Portapapeles", w / 2f, top + clipHeaderH / 2f - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        if (clips.any { it !in clipPinned }) {
            textPaint.typeface = Typeface.DEFAULT
            textPaint.textSize = sp(12.5f)
            textPaint.color = t.accent2
            c.drawText("Borrar todo", w - dp(48f), top + clipHeaderH / 2f - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        }
        val listTop = top + clipHeaderH
        c.save()
        c.clipRect(0f, listTop, w, height.toFloat())
        if (clips.isEmpty()) {
            textPaint.typeface = Typeface.DEFAULT
            textPaint.textSize = sp(13f)
            textPaint.color = t.textMuted
            c.drawText("Copia algo y aparecerá aquí", w / 2f, listTop + dp(60f), textPaint)
            textPaint.textSize = sp(12f)
            c.drawText("Fija con el alfiler lo que quieras guardar siempre", w / 2f, listTop + dp(84f), textPaint)
        }
        clips.forEachIndexed { i, clip ->
            val y0 = listTop + i * clipItemH - clipScroll
            if (y0 > height || y0 + clipItemH < listTop) return@forEachIndexed
            keyPaint.shader = null
            keyPaint.color = t.key
            rect.set(dp(8f), y0 + dp(3f), w - dp(8f), y0 + clipItemH - dp(3f))
            c.drawRoundRect(rect, dp(10f), dp(10f), keyPaint)
            val one = clip.replace('\n', ' ').trim()
            val pinned = clip in clipPinned
            drawPinIcon(c, w - dp(78f), rect.centerY(), dp(7f), if (pinned) t.accent2 else t.textMuted, pinned)
            textPaint.typeface = Typeface.DEFAULT
            textPaint.textSize = sp(14f)
            textPaint.color = t.text
            textPaint.textAlign = Paint.Align.LEFT
            val maxW = w - dp(114f)
            var shown = one
            if (textPaint.measureText(shown) > maxW) {
                while (shown.isNotEmpty() && textPaint.measureText("$shown…") > maxW) shown = shown.dropLast(1)
                shown = "$shown…"
            }
            c.drawText(shown, dp(20f), rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = t.textMuted
            textPaint.textSize = sp(16f)
            c.drawText("✕", w - dp(32f), rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        }
        c.restore()
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun clipTap(x: Float, y: Float) {
        val w = width.toFloat()
        if (y < topH + clipHeaderH) {
            when {
                x < dp(70f) -> { listener.onPressFeedback(0); listener.onKey(Key("ABC", Codes.LETTERS)) }
                x > w - dp(100f) && clips.any { it !in clipPinned } -> { listener.onPressFeedback(0); listener.onClipClear() }
            }
            return
        }
        val i = ((y - topH - clipHeaderH + clipScroll) / clipItemH).toInt()
        if (i !in clips.indices) return
        listener.onPressFeedback(0)
        when {
            x > w - dp(56f) -> listener.onClipDelete(i)
            x > w - dp(102f) -> listener.onClipPin(i)
            else -> listener.onClipPick(i)
        }
    }

    // ------------------------------------------------------------------ toolbar / paste chip
    /** The service asks for the toolbar (field empty, nothing typed yet, text selected…). */
    private var toolbarWanted = false
    /** Set when you tap the toolbar button; cleared when you start typing. */
    private var toolbarManual: Boolean? = null
    /** Text you just copied, offered as a chip to paste it with one tap. */
    private var clipChip: String? = null
    private val sideW get() = dp(44f)
    private val toolCount = 4

    fun setStrip(toolbar: Boolean, chip: String?) {
        if (toolbarWanted == toolbar && clipChip == chip) return
        toolbarWanted = toolbar
        clipChip = chip
        invalidate()
    }

    fun clearToolbarOverride() {
        if (toolbarManual != null) {
            toolbarManual = null
            invalidate()
        }
    }

    /** What the first row shows: 0 suggestions, 1 toolbar, 2 paste chip. */
    private fun stripContent(): Int {
        if (clipMode || editMode) return 1   // panel open: its toolbar icon shows it (tap again to close)
        val hasSug = suggestions.any { it.isNotEmpty() }
        return when (toolbarManual) {
            true -> 1
            false -> if (hasSug) 0 else if (clipChip != null) 2 else 1
            else -> when {
                clipChip != null -> 2
                toolbarWanted || !hasSug -> 1
                else -> 0
            }
        }
    }

    private fun toolCellW() = (width - sideW * 2) / toolCount

    private fun drawToolbarButton(c: Canvas, cx: Float, cy: Float, t: KbTheme, active: Boolean) {
        val pressed = mode == Mode.STRIP && stripPressed == -6
        if (active || pressed) {
            keyPaint.shader = null
            keyPaint.color = if (active) t.accent else t.keyPressed
            c.drawCircle(cx, cy, dp(15f), keyPaint)
        }
        // 2x2 grid of rounded squares
        val col = if (active) Color.WHITE else t.text
        keyPaint.shader = null
        keyPaint.color = col
        val s = dp(4.2f)
        val g = dp(1.6f)
        for (i in 0..1) for (j in 0..1) {
            val l = cx - g / 2f - s + i * (s + g)
            val tp = cy - g / 2f - s + j * (s + g)
            rect.set(l, tp, l + s, tp + s)
            c.drawRoundRect(rect, dp(1.3f), dp(1.3f), keyPaint)
        }
    }

    private fun drawToolbar(c: Canvas, t: KbTheme) {
        val cw = toolCellW()
        val cy = sugH / 2f
        for (i in 0 until toolCount) {
            val cx = sideW + cw * (i + 0.5f)
            val active = when (i) {
                0 -> clipMode
                1 -> editMode
                2 -> oneHand > 0
                else -> false
            }
            val pressed = mode == Mode.STRIP && stripPressed == -10 - i
            if (active || pressed) {
                keyPaint.shader = null
                keyPaint.color = if (active) t.accent else t.keyPressed
                c.drawCircle(cx, cy, dp(15f), keyPaint)
            }
            val col = if (active) Color.WHITE else t.text
            when (i) {
                0 -> drawClipIcon(c, cx, cy, dp(8f), col)
                1 -> drawCursorIcon(c, cx, cy, dp(8f), col)
                2 -> drawOneHandIcon(c, cx, cy, dp(8f), col)
                else -> drawGearIcon(c, cx, cy, dp(8f), col)
            }
        }
    }

    private fun drawChip(c: Canvas, t: KbTheme) {
        val chip = clipChip ?: return
        val l = sideW + dp(4f)
        val maxR = width - sideW - dp(4f)
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = sp(14f)
        val one = chip.replace('\n', ' ').trim()
        val maxText = maxR - l - dp(44f)
        var shown = one
        if (textPaint.measureText(shown) > maxText) {
            while (shown.isNotEmpty() && textPaint.measureText("$shown…") > maxText) shown = shown.dropLast(1)
            shown = "$shown…"
        }
        val r = min(maxR, l + dp(40f) + textPaint.measureText(shown))
        val pressed = mode == Mode.STRIP && stripPressed == -7
        rect.set(l, dp(3f), r, sugH - dp(3f))
        keyPaint.shader = null
        keyPaint.color = if (pressed) t.keyPressed else withAlpha(t.accent, 70)
        c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, keyPaint)
        linePaint.shader = null
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = dp(1f)
        linePaint.color = withAlpha(t.accent, 200)
        c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, linePaint)
        linePaint.style = Paint.Style.FILL
        drawClipIcon(c, l + dp(17f), sugH / 2f, dp(6.5f), t.text)
        textPaint.color = t.text
        textPaint.textAlign = Paint.Align.LEFT
        c.drawText(shown, l + dp(30f), sugH / 2f - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    /** Text cursor (I-beam) with little arrows: the text editing panel. */
    private fun drawCursorIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        linePaint.shader = null
        linePaint.color = color
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = dp(1.7f)
        linePaint.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx, cy - s, cx, cy + s, linePaint)
        c.drawLine(cx - s * 0.35f, cy - s, cx + s * 0.35f, cy - s, linePaint)
        c.drawLine(cx - s * 0.35f, cy + s, cx + s * 0.35f, cy + s, linePaint)
        linePaint.style = Paint.Style.FILL
        triangle(c, cx - s * 1.25f, cy, s * 0.42f, 180f, color)
        triangle(c, cx + s * 1.25f, cy, s * 0.42f, 0f, color)
    }

    /** Small keyboard pushed to one side: one-handed mode. */
    private fun drawOneHandIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        linePaint.shader = null
        linePaint.color = color
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = dp(1.5f)
        rect.set(cx - s * 1.2f, cy - s * 0.8f, cx + s * 1.2f, cy + s * 0.8f)
        c.drawRoundRect(rect, s * 0.25f, s * 0.25f, linePaint)
        linePaint.style = Paint.Style.FILL
        rect.set(cx - s * 0.2f, cy - s * 0.5f, cx + s * 0.95f, cy + s * 0.5f)
        c.drawRoundRect(rect, s * 0.15f, s * 0.15f, linePaint)
        triangle(c, cx - s * 0.7f, cy, s * 0.32f, 180f, color)
    }

    private val triPath = android.graphics.Path()

    /** Filled triangle pointing at [angle] degrees (0 = right, 90 = down, 180 = left, 270 = up). */
    private fun triangle(c: Canvas, cx: Float, cy: Float, r: Float, angle: Float, color: Int) {
        keyPaint.shader = null
        keyPaint.color = color
        c.save()
        c.rotate(angle, cx, cy)
        triPath.reset()
        triPath.moveTo(cx + r, cy)
        triPath.lineTo(cx - r * 0.6f, cy - r * 0.95f)
        triPath.lineTo(cx - r * 0.6f, cy + r * 0.95f)
        triPath.close()
        c.drawPath(triPath, keyPaint)
        c.restore()
    }

    // ------------------------------------------------------------------ text editing panel
    var editMode = false
        private set
    private var editSelecting = false
    private var editPressed: ECell? = null

    private class ECell(val action: Int, val label: String, val col: Int, val row: Int, val span: Int = 1) {
        val repeats get() = action in listOf(E_LEFT, E_RIGHT, E_UP, E_DOWN, E_DEL)
    }

    private val editCells = listOf(
        ECell(E_HOME, "⇤", 0, 0), ECell(E_UP, "", 1, 0), ECell(E_END, "⇥", 2, 0), ECell(E_SELALL, "Seleccionar todo", 3, 0),
        ECell(E_LEFT, "", 0, 1), ECell(E_SELECT, "Seleccionar", 1, 1), ECell(E_RIGHT, "", 2, 1), ECell(E_COPY, "Copiar", 3, 1),
        ECell(E_UNDO, "Deshacer", 0, 2), ECell(E_DOWN, "", 1, 2), ECell(E_DEL, "⌫", 2, 2), ECell(E_CUT, "Cortar", 3, 2),
        ECell(E_BACK, "ABC", 0, 3, span = 3), ECell(E_PASTE, "Pegar", 3, 3)
    )

    fun showEditPanel() {
        emojiMode = false
        clipMode = false
        editMode = true
        editSelecting = false
        cancelGesture()
        invalidate()
    }

    /** Bounds of a cell (without the gap between keys) into [rect]. */
    private fun editBounds(cell: ECell) {
        val w = width.toFloat()
        val top = topH + padY
        val rh = (height - padY - top) / 4f
        val colW = (w - padX * 2) * 0.22f
        val l = padX + colW * min(cell.col, 3)
        val r = if (cell.col < 3) l + colW * cell.span else w - padX
        rect.set(l, top + rh * cell.row, r, top + rh * (cell.row + 1))
    }

    private fun editCellAt(x: Float, y: Float): ECell? {
        for (cell in editCells) {
            editBounds(cell)
            if (rect.contains(x, y)) return cell
        }
        return null
    }

    private fun drawEditPanel(c: Canvas, t: KbTheme) {
        val gapX = dp(keyGapX())
        val gapY = dp(keyGapY())
        val radius = dp(if (keySpacing == 0) 7f else 8f)
        for (cell in editCells) {
            editBounds(cell)
            rect.set(rect.left + gapX / 2f, rect.top + gapY / 2f, rect.right - gapX / 2f, rect.bottom - gapY / 2f)
            val active = cell.action == E_SELECT && editSelecting
            val base = if (cell.col == 3 || cell.action == E_BACK || cell.action == E_UNDO) t.keySpecial else t.key
            keyPaint.shader = null
            keyPaint.color = darker(base)
            rect.offset(0f, dp(1.2f))
            c.drawRoundRect(rect, radius, radius, keyPaint)
            rect.offset(0f, -dp(1.2f))
            keyPaint.color = when {
                editPressed === cell && mode == Mode.EDIT -> t.keyPressed
                active -> t.accent
                else -> base
            }
            c.drawRoundRect(rect, radius, radius, keyPaint)
            val col = if (active) Color.WHITE else t.text
            val cx = rect.centerX()
            val cy = rect.centerY()
            val ar = min(rect.height(), rect.width()) * 0.16f
            when (cell.action) {
                E_UP -> triangle(c, cx, cy, ar, 270f, col)
                E_DOWN -> triangle(c, cx, cy, ar, 90f, col)
                E_LEFT -> triangle(c, cx, cy, ar, 180f, col)
                E_RIGHT -> triangle(c, cx, cy, ar, 0f, col)
                else -> {
                    textPaint.color = col
                    if (cell.label.length <= 3) {
                        textPaint.typeface = if (cell.action == E_BACK) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        textPaint.textSize = sp(if (cell.action == E_BACK) 14f else 20f)
                    } else {
                        textPaint.typeface = Typeface.DEFAULT
                        fitText(cell.label, rect.width() - dp(10f), 13.5f)
                    }
                    c.drawText(cell.label, cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
                }
            }
        }
        textPaint.typeface = Typeface.DEFAULT
    }

    private val editRepeat = object : Runnable {
        override fun run() {
            val cell = editPressed ?: return
            if (mode != Mode.EDIT) return
            fireEdit(cell)
            postDelayed(this, 60)
        }
    }

    private fun fireEdit(cell: ECell) {
        when (cell.action) {
            E_SELECT -> editSelecting = !editSelecting
            E_BACK -> listener.onKey(Key("ABC", Codes.LETTERS))
            else -> {
                listener.onEditAction(cell.action, editSelecting)
                when (cell.action) {
                    E_SELALL -> editSelecting = true
                    E_COPY, E_CUT, E_PASTE, E_DEL, E_UNDO -> editSelecting = false
                }
            }
        }
        invalidate()
    }

    // ------------------------------------------------------------------ one-handed side panel
    /** 0 = not on the side panel, -1 = side panel (no button), 1 = expand, 2 = move to the other side. */
    private fun oneHandHit(x: Float, y: Float): Int {
        if (oneHand == 0 || y < topH) return 0
        val w = width.toFloat()
        val kw = kbWidth(w)
        val inPanel = if (oneHand == 1) x >= kw else x < w - kw
        if (!inPanel) return 0
        val mid = topH + (height - topH) / 2f
        return if (y < mid) 1 else 2
    }

    private fun drawOneHandPanel(c: Canvas, t: KbTheme) {
        val w = width.toFloat()
        val kw = kbWidth(w)
        val pl = if (oneHand == 1) kw else 0f
        val pr = if (oneHand == 1) w else w - kw
        val cx = (pl + pr) / 2f
        val area = height - topH
        val r = min(dp(21f), (pr - pl) / 2f - dp(5f))
        for (b in 1..2) {
            val cy = topH + area * (if (b == 1) 0.32f else 0.68f)
            keyPaint.shader = null
            keyPaint.color = t.keySpecial
            c.drawCircle(cx, cy, r, keyPaint)
            if (b == 1) {
                // expand: double arrow
                linePaint.shader = null
                linePaint.color = t.text
                linePaint.style = Paint.Style.STROKE
                linePaint.strokeWidth = dp(1.8f)
                c.drawLine(cx - r * 0.45f, cy, cx + r * 0.45f, cy, linePaint)
                linePaint.style = Paint.Style.FILL
                triangle(c, cx - r * 0.5f, cy, r * 0.22f, 180f, t.text)
                triangle(c, cx + r * 0.5f, cy, r * 0.22f, 0f, t.text)
            } else {
                // move the keys to this side
                triangle(c, cx, cy, r * 0.3f, if (oneHand == 2) 180f else 0f, t.text)
            }
        }
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = sp(10f)
        textPaint.color = t.textMuted
        c.drawText("Ampliar", cx, topH + area * 0.32f + r + dp(14f), textPaint)
        c.drawText("Mover", cx, topH + area * 0.68f + r + dp(14f), textPaint)
    }

    // ------------------------------------------------------------------ voice typing
    var voiceMode = false
        private set
    private var voiceText = ""
    private var voiceLevel = 0f
    private var voiceStart = 0L

    fun setVoice(on: Boolean, text: String = "") {
        if (on && !voiceMode) voiceStart = SystemClock.uptimeMillis()
        voiceMode = on
        voiceText = text
        if (on) cancelGesture()
        invalidate()
    }

    /** Microphone loudness (rms dB from the recognizer, about -2..10). */
    fun setVoiceLevel(rms: Float) {
        voiceLevel = voiceLevel * 0.6f + ((rms + 2f) / 12f).coerceIn(0f, 1f) * 0.4f
        if (voiceMode) invalidate()
    }

    private fun drawMicIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        linePaint.shader = null
        linePaint.color = color
        linePaint.style = Paint.Style.FILL
        rect.set(cx - s * 0.32f, cy - s, cx + s * 0.32f, cy + s * 0.25f)
        c.drawRoundRect(rect, s * 0.32f, s * 0.32f, linePaint)
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = s * 0.16f
        linePaint.strokeCap = Paint.Cap.ROUND
        rect.set(cx - s * 0.62f, cy - s * 0.55f, cx + s * 0.62f, cy + s * 0.62f)
        c.drawArc(rect, 0f, 180f, false, linePaint)
        c.drawLine(cx, cy + s * 0.62f, cx, cy + s * 0.95f, linePaint)
        linePaint.style = Paint.Style.FILL
    }

    private fun drawMicButton(c: Canvas, cx: Float, cy: Float, t: KbTheme) {
        if (voiceMode) {
            keyPaint.shader = null
            keyPaint.color = t.accent
            c.drawCircle(cx, cy, dp(15f), keyPaint)
            drawMicIcon(c, cx, cy, dp(8f), Color.WHITE)
        } else {
            if (mode == Mode.STRIP && stripPressed == -3) {
                keyPaint.shader = null
                keyPaint.color = t.keyPressed
                c.drawCircle(cx, cy, dp(15f), keyPaint)
            }
            drawMicIcon(c, cx, cy, dp(8f), t.text)
        }
    }

    private fun drawVoicePanel(c: Canvas, t: KbTheme) {
        val w = width.toFloat()
        val top = topH
        val cx = w / 2f
        val cy = top + (height - top) * 0.40f
        val now = SystemClock.uptimeMillis()
        // pulsing rings driven by the voice level
        val base = dp(34f)
        val breathe = 0.5f + 0.5f * sin((now - voiceStart) / 260.0).toFloat()
        for (i in 3 downTo 1) {
            keyPaint.shader = null
            keyPaint.color = withAlpha(t.accent, 35 + 10 * (3 - i))
            c.drawCircle(cx, cy, base + i * dp(9f) * (0.35f + voiceLevel + 0.15f * breathe), keyPaint)
        }
        keyPaint.color = t.accent
        c.drawCircle(cx, cy, base, keyPaint)
        drawMicIcon(c, cx, cy, dp(15f), Color.WHITE)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.color = t.text
        val shown = if (voiceText.isEmpty()) "Habla ahora…" else voiceText
        fitText(shown, w - dp(32f), 17f)
        c.drawText(shown, cx, cy + base + dp(40f), textPaint)
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = sp(12f)
        textPaint.color = t.textMuted
        c.drawText("Toca para terminar", cx, height - dp(16f), textPaint)
        postInvalidateOnAnimation()
    }

    fun setSuggestions(s: List<String>, bold: Int) {
        suggestions = if (s.size >= 3) s.take(3) else s + List(3 - s.size) { "" }
        boldIndex = bold
        if (removeAsk != null && removeAsk !in suggestions) removeAsk = null
        invalidate()
    }

    /** Emoji picked from the suggestion bar also goes to the recent emojis. */
    fun addRecentEmoji(e: String) {
        if (emojiCats.isEmpty()) loadEmojis()
        addRecent(e)
    }

    // ---- "remove this suggestion?" (long-press on a suggestion)
    private var removeAsk: String? = null

    private val sugLongPress = Runnable {
        val i = stripPressed
        if (mode == Mode.STRIP && i in 0..2 && stripContent() == 0) {
            val w = suggestions[i]
            if (w.any { it.isLetter() }) {
                removeAsk = w
                mode = Mode.NONE      // releasing the finger won't pick it
                stripPressed = -1
                listener.onPressFeedback(0)
                invalidate()
            }
        }
    }

    private fun drawRemoveAsk(c: Canvas, t: KbTheme, word: String) {
        val w = width.toFloat()
        val cy = sugH / 2f
        // [ Quitar ] [ ✕ ] on the right, question on the left
        val cancelL = w - dp(46f)
        val quitR = cancelL - dp(4f)
        val quitL = quitR - dp(84f)
        keyPaint.shader = null
        keyPaint.color = if (mode == Mode.STRIP && stripPressed == -20) t.keyPressed else t.accent
        rect.set(quitL, dp(4f), quitR, sugH - dp(4f))
        c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, keyPaint)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = sp(13f)
        textPaint.color = Color.WHITE
        c.drawText("Quitar", rect.centerX(), cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        if (mode == Mode.STRIP && stripPressed == -21) {
            keyPaint.color = t.keyPressed
            c.drawCircle(w - dp(23f), cy, dp(14f), keyPaint)
        }
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = sp(16f)
        textPaint.color = t.text
        c.drawText("✕", w - dp(23f), cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        val q = "¿Dejar de sugerir «$word»?"
        textPaint.textAlign = Paint.Align.LEFT
        fitText(q, quitL - dp(18f), 14f)
        textPaint.color = t.text
        c.drawText(q, dp(12f), cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    fun setXp(level: Int, frac: Float, line: String, combo: Int, streak: Int) {
        this.streak = streak
        val now = SystemClock.uptimeMillis()
        if (level > this.level && this.level > 0 && animStart > 0) {
            animFrom = 0f; animTo = frac; animStart = now
        } else if (level == this.level && frac > xpFrac + 0.0005f) {
            animFrom = currentBlue(now); animTo = frac; animStart = now
        } else {
            animFrom = frac; animTo = frac
            if (animStart == 0L) animStart = 1L
        }
        this.level = level
        this.xpFrac = frac
        this.xpLine = line
        this.combo = combo
        comboExpire = SystemClock.uptimeMillis() + 5000
        invalidate()
    }

    fun addFloatingXp(amount: Int) {
        if (!showFloatingXp || amount <= 0) return
        val x = width * (0.35f + Random.nextFloat() * 0.3f)
        val y = topH + rowH * 1.2f
        floats.add(FloatText("+$amount XP", x, y, SystemClock.uptimeMillis(), theme.accent2))
        if (floats.size > 6) floats.removeAt(0)
        postInvalidateOnAnimation()
    }

    /** Level up: no message, the number itself animates (old one shrinks away, new one pops in with a glow). */
    @Suppress("UNUSED_PARAMETER")
    fun showLevelUp(newLevel: Int, title: String, extra: String) {
        levelAnimFrom = level
        levelAnimStart = SystemClock.uptimeMillis()
        pendingBurst = true
        postInvalidateOnAnimation()
    }

    private var levelAnimFrom = 0
    private var levelAnimStart = 0L
    private val levelOutMs = 150L
    private val levelInMs = 420L
    private var pendingBurst = false
    private var badgeX = 0f
    private var badgeY = 0f

    /** Draws the badge with the level-change animation. Returns true while animating. */
    private fun drawAnimatedBadge(c: Canvas, t: KbTheme, cx: Float, cy: Float, r: Float, blue: Float, pressed: Boolean, now: Long): Boolean {
        badgeX = cx; badgeY = cy
        val e = now - levelAnimStart
        if (levelAnimStart == 0L || e >= levelOutMs + levelInMs) {
            renderer.drawBadge(c, xpStyle, t, cx, cy, r, level, blue, pressed)
            return false
        }
        if (e < levelOutMs) {
            // old number shrinks and fades upward
            val p = e.toFloat() / levelOutMs
            val sc = 1f - 0.45f * p
            c.save()
            c.translate(0f, -dp(6f) * p)
            c.scale(sc, sc, cx, cy)
            renderer.drawBadge(c, xpStyle, t, cx, cy, r, levelAnimFrom, 1f, pressed)
            c.restore()
        } else {
            pendingBurst = false
            val p = (e - levelOutMs).toFloat() / levelInMs
            // golden glow ring
            keyPaint.shader = null
            keyPaint.color = withAlpha(Color.parseColor("#FFD54A"), (150 * (1f - p)).toInt())
            c.drawCircle(cx, cy, r * (1.1f + 0.7f * p), keyPaint)
            val pop = 1f + 0.4f * sin(Math.PI * min(1f, p * 1.4f)).toFloat()
            val sc = (0.55f + 0.45f * min(1f, p * 3f)) * pop
            c.save()
            c.scale(sc, sc, cx, cy)
            renderer.drawBadge(c, xpStyle, t, cx, cy, r, level, blue, pressed)
            c.restore()
        }
        return true
    }

    fun showBanner(title: String, subtitle: String) {
        banners.add(Banner(title, subtitle, false))
        if (banners.size == 1) bannerStart = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    /** Key centers (pixels) of the letters layout, keyed by lowercase letter. */
    /** Key centers (pixels) moved toward where you usually press (see [TouchModel]). */
    fun letterCenters(): Map<Char, FloatArray> {
        val m = HashMap<Char, FloatArray>()
        for (k in letters.allKeys) if (k.isLetter) {
            val o = TouchModel.offset(k.label[0])
            m[k.label[0]] = if (o == null) floatArrayOf(k.cx, k.cy) else floatArrayOf(k.cx + o[0] * unitW, k.cy + o[1] * rowH)
        }
        return m
    }

    /** Real (unshifted) key centers in pixels. */
    fun rawLetterCenters(): Map<Char, FloatArray> {
        val m = HashMap<Char, FloatArray>()
        for (k in letters.allKeys) if (k.isLetter) m[k.label[0]] = floatArrayOf(k.cx, k.cy)
        return m
    }

    // ================================================================== layout

    /** The number row is a bit shorter than the letter rows. */
    private val numRowFrac = 0.8f
    private fun keyAreaH() = rowH * (4f + if (numberRow) numRowFrac else 0f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (topH + keyAreaH() + padY * 2).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout(w, h)
    }

    private fun relayout(w: Int, h: Int) {
        bgShader = null
        val kw = kbWidth(w.toFloat())
        val kl = kbLeft(w.toFloat())
        unitW = (kw - padX * 2) / 10f
        for (l in listOf(letters, symbols, symbols2)) layoutKeys(l, kl, kw)
        // emoji bottom bar (same height as a key row, at the bottom, always full width)
        val ew = (w - padX * 2) / 10f
        var x = padX
        val barTop = h - padY - rowH * 0.9f
        for (k in emojiBar) {
            k.left = x
            k.right = x + k.width * ew
            k.top = barTop
            k.bottom = h - padY
            x = k.right
        }
    }

    /** All layouts fill the same height: with fewer rows (symbols) each row is a bit taller. */
    private fun layoutKeys(l: KbLayout, left: Float, kw: Float) {
        val weights = l.rows.indices.map { r -> if (l.numberRow && r == 0) numRowFrac else 1f }
        val scale = keyAreaH() / (rowH * weights.sum())
        var top = topH + padY
        l.rows.forEachIndexed { r, row ->
            val rh = rowH * weights[r] * scale
            val units = row.sumOf { it.width.toDouble() }.toFloat()
            var x = left + (kw - units * unitW) / 2f
            for (k in row) {
                k.left = x
                k.right = x + k.width * unitW
                k.top = top
                k.bottom = top + rh
                x = k.right
            }
            top += rh
        }
    }

    /** Center of the "q" key (pixels): origin used to compare swipes when the keyboard moves. */
    fun canonOrigin(): FloatArray {
        val q = letters.allKeys.firstOrNull { it.label == "q" } ?: return floatArrayOf(0f, 0f)
        return floatArrayOf(q.cx, q.cy)
    }

    // ================================================================== drawing

    override fun onDraw(canvas: Canvas) {
        val saved = canvas.save()
        try {
            drawAll(canvas)
        } catch (e: Exception) {
            // never let a drawing problem close the keyboard
            CrashLog.log(e, "dibujo")
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private fun drawAll(canvas: Canvas) {
        val t = theme
        val now = SystemClock.uptimeMillis()
        val w = width.toFloat()

        val img = bgImage
        if (img != null && width > 0 && height > 0) {
            val r = CustomTheme.visibleRect(img.width, img.height, height.toFloat() / width, bgPos)
            srcRect.set(r[0], r[1], r[2], r[3])
            dstRect.set(0f, 0f, w, height.toFloat())
            bgPaint.shader = null
            bgPaint.color = Color.BLACK
            bgPaint.isFilterBitmap = true
            canvas.drawBitmap(img, srcRect, dstRect, bgPaint)
            if (t.dim > 0) {
                bgPaint.color = Color.argb(t.dim * 255 / 100, 0, 0, 0)
                canvas.drawRect(0f, 0f, w, height.toFloat(), bgPaint)
            }
        } else {
            if (bgShader == null) bgShader = LinearGradient(0f, 0f, 0f, height.toFloat(), t.bgTop, t.bgBottom, Shader.TileMode.CLAMP)
            bgPaint.shader = bgShader
            canvas.drawRect(0f, 0f, w, height.toFloat(), bgPaint)
        }

        drawStrip(canvas, now)
        drawKeys(canvas, now)
        val trailAnim = drawTrail(canvas, now)
        drawPreview(canvas)
        drawPopup(canvas)
        drawGlideBubble(canvas)
        val animating = drawFloats(canvas, now) or drawParticles(canvas, now) or drawBanner(canvas, now)
        if (animating || trailAnim) postInvalidateOnAnimation()
    }

    private fun drawStrip(c: Canvas, now: Long) {
        val t = theme
        val w = width.toFloat()

        // ---- row 1: [toolbar button] suggestions / toolbar / paste chip [mic]
        val ask = removeAsk
        if (ask != null) drawRemoveAsk(c, t, ask) else drawRow1(c, t, w)
        drawRow2(c, t, w, now)
    }

    private fun drawRow1(c: Canvas, t: KbTheme, w: Float) {
        drawMicButton(c, w - sideW / 2f, sugH / 2f, t)
        val content = stripContent()
        drawToolbarButton(c, sideW / 2f, sugH / 2f, t, content == 1)
        when (content) {
            1 -> drawToolbar(c, t)
            2 -> drawChip(c, t)
            else -> {
                val slotW = (w - sideW * 2) / 3f
                val textY = sugH / 2f + dp(1f)
                for (i in 0..2) {
                    val s = suggestions[i]
                    val x0 = sideW + slotW * i
                    if (mode == Mode.STRIP && stripPressed == i && s.isNotEmpty()) {
                        keyPaint.shader = null
                        keyPaint.color = withAlpha(t.keyPressed, 160)
                        rect.set(x0 + dp(2f), dp(2f), x0 + slotW - dp(2f), sugH - dp(1f))
                        c.drawRoundRect(rect, dp(8f), dp(8f), keyPaint)
                    }
                    if (i > 0) {
                        linePaint.shader = null
                        linePaint.color = withAlpha(t.textMuted, 60)
                        c.drawRect(x0 - dp(0.5f), dp(8f), x0 + dp(0.5f), sugH - dp(6f), linePaint)
                    }
                    if (s.isEmpty()) continue
                    textPaint.typeface = if (i == boldIndex) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    textPaint.color = if (i == boldIndex) t.accent2 else t.text
                    fitText(s, slotW - dp(10f), 17f)
                    c.drawText(s, x0 + slotW / 2f, textY - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
                }
            }
        }
    }

    private fun drawRow2(c: Canvas, t: KbTheme, w: Float, now: Long) {
        // ---- row 2: level circle + xp bar, xp and streak under the bar
        val r = dp(14f)
        val bcx = padX + dp(5f) + r
        val bcy = sugH + (topH - sugH) / 2f
        val barCy = bcy - dp(5f)
        val blue = currentBlue(now)
        val animating = animTo > blue + 0.0001f
        val whiteAlpha = (255 * min(1f, (now - animStart).toFloat() / 120f)).toInt()
        val pressedRow = stripPressed == -2 && mode == Mode.STRIP
        if (xpStyle == "bloques") {
            // block-game layout: full-width bar with the level number centered on top of it
            val bl = padX + dp(10f)
            val br = w - padX - dp(10f)
            val cy2 = bcy + dp(5f)
            renderer.drawBar(c, xpStyle, t, bl, br, cy2, blue, level, if (animating) animTo else blue, whiteAlpha)
            val lvlAnim = drawAnimatedBadge(c, t, w / 2f, cy2 - dp(11f), r, blue, pressedRow, now)
            if (pendingXp > 0) {
                textPaint.typeface = Typeface.DEFAULT_BOLD
                textPaint.textSize = sp(12f)
                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.color = Color.parseColor("#80FF20")
                c.drawText("+$pendingXp", br, cy2 - dp(8f), textPaint)
                textPaint.textAlign = Paint.Align.CENTER
            }
            if (animating || lvlAnim) postInvalidateOnAnimation()
            textPaint.typeface = Typeface.DEFAULT
            return
        }
        val lvlAnim = drawAnimatedBadge(c, t, bcx, bcy, r, blue, pressedRow, now)
        if (lvlAnim) postInvalidateOnAnimation()
        val barL = bcx + r + dp(8f)
        val barR = w - padX - dp(5f)
        renderer.drawBar(c, xpStyle, t, barL, barR, barCy, blue, level,
            if (animating) animTo else blue, whiteAlpha, circleR = r, circleCy = bcy)
        if (animating) postInvalidateOnAnimation()
        val textR = if (xpStyle == "club") barR - r * 2 - dp(7f) else barR

        val ty = bcy + dp(11f)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = sp(11f)
        textPaint.color = t.text
        c.drawText(xpLine, (barL + textR) / 2f, ty, textPaint)
        if (pendingXp > 0) {
            // XP earned in this burst of typing, added to the bar when you pause
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.color = t.accent2
            c.drawText("+$pendingXp", textR, ty, textPaint)
            textPaint.textAlign = Paint.Align.CENTER
        }
        if (combo >= 5 && now < comboExpire) {
            val label = "⚡x" + String.format("%.1f", XpManager.comboMultiplier(combo))
            val pulse = 1f + 0.08f * sin(now / 120.0).toFloat()
            textPaint.textSize = sp(11f) * pulse
            textPaint.color = t.accent
            textPaint.textAlign = Paint.Align.LEFT
            c.drawText(label, barL, ty, textPaint)
            textPaint.textAlign = Paint.Align.CENTER
            postInvalidateDelayed(60)
        } else if (combo >= 5 && now - comboExpire < 200) {
            postInvalidateDelayed(60)
        }
        textPaint.typeface = Typeface.DEFAULT
    }

    private fun currentBlue(now: Long): Float {
        val e = now - animStart
        return when {
            e < animWhiteMs -> animFrom
            e < animWhiteMs + animFillMs -> {
                val p = (e - animWhiteMs).toFloat() / animFillMs
                val ease = 1f - (1f - p) * (1f - p)
                animFrom + (animTo - animFrom) * ease
            }
            else -> animTo
        }
    }

    private fun fitText(s: String, maxW: Float, baseSp: Float) {
        textPaint.textSize = sp(baseSp)
        val mw = textPaint.measureText(s)
        if (mw > maxW && mw > 0f) textPaint.textSize = max(sp(10f), sp(baseSp) * maxW / mw)
    }

    private fun drawKeys(c: Canvas, now: Long) {
        val t = theme
        val gapX = dp(keyGapX())
        val gapY = dp(keyGapY())
        val radius = dp(if (keySpacing == 0) 7f else 8f)
        val pressed = if (mode == Mode.PRESS || mode == Mode.POPUP || mode == Mode.CURSOR || mode == Mode.DELSWIPE) curKey else null
        var idx = 0
        if (voiceMode) {
            drawVoicePanel(c, t)
            return
        }
        if (clipMode) {
            drawClipPanel(c, t)
            return
        }
        if (editMode) {
            drawEditPanel(c, t)
            return
        }
        if (emojiMode) drawEmojiPanel(c)
        val keys = if (emojiMode) emojiBar else layout.allKeys
        for (k in keys) {
            idx++
            val special = k.isSpecial && k.code != Codes.SPACE
            val base = if (special) t.keySpecial else t.key
            rect.set(k.left + gapX / 2f, k.top + gapY / 2f, k.right - gapX / 2f, k.bottom - gapY / 2f)
            // shadow (in 3D: a thick edge below the key)
            keyPaint.shader = null
            val depth = if (keys3d) dp(3.5f) else dp(1.2f)
            keyPaint.color = if (keys3d) {
                if (Color.alpha(base) < 200) Color.argb(150, 0, 0, 0) else darker(darker(base))
            } else darker(base)
            rect.offset(0f, depth)
            c.drawRoundRect(rect, radius, radius, keyPaint)
            rect.offset(0f, -depth)
            if (keys3d && k === pressed) rect.offset(0f, depth * 0.6f)   // pressed key goes down
            keyPaint.color = if (k === pressed) t.keyPressed else base
            if (k.code == Codes.ENTER) keyPaint.color = if (k === pressed) t.keyPressed else t.accent
            if (k.code == Codes.SHIFT && shiftState > 0) keyPaint.color = if (shiftState == 2) t.accent else t.keyPressed
            c.drawRoundRect(rect, radius, radius, keyPaint)
            if (keys3d) {
                // light from above: brighter top half
                keyPaint.color = Color.argb(if (k === pressed) 10 else 34, 255, 255, 255)
                val bottom = rect.bottom
                rect.bottom = rect.top + rect.height() * 0.48f
                c.drawRoundRect(rect, radius, radius, keyPaint)
                rect.bottom = bottom
            }
            if (t.border != 0) {
                linePaint.shader = null
                linePaint.style = Paint.Style.STROKE
                linePaint.strokeWidth = dp(1.5f)
                linePaint.color = t.border
                c.drawRoundRect(rect, radius, radius, linePaint)
                linePaint.style = Paint.Style.FILL
            }
            if (t.rainbow) {
                val hue = ((idx * 13) % 360).toFloat()
                linePaint.style = Paint.Style.STROKE
                linePaint.strokeWidth = dp(1.4f)
                linePaint.color = Color.HSVToColor(200, floatArrayOf(hue, 0.75f, 1f))
                c.drawRoundRect(rect, radius, radius, linePaint)
                linePaint.style = Paint.Style.FILL
            }

            // label
            var label = k.label
            textPaint.typeface = Typeface.DEFAULT
            textPaint.color = t.text
            when (k.code) {
                Codes.SPACE -> {
                    label = spaceLabel
                    textPaint.textSize = sp(12f)
                    textPaint.color = t.textMuted
                }
                Codes.ENTER -> {
                    label = enterLabel
                    textPaint.textSize = sp(20f)
                    textPaint.color = Color.WHITE
                }
                Codes.SHIFT -> {
                    label = if (shiftState == 2) "⇪" else "⇧"
                    textPaint.textSize = sp(22f)
                    if (shiftState == 2) textPaint.color = Color.WHITE
                }
                Codes.DELETE -> textPaint.textSize = sp(20f)
                Codes.SYMBOLS, Codes.LETTERS, Codes.SYMBOLS2 -> {
                    textPaint.textSize = sp(14f)
                    textPaint.typeface = Typeface.DEFAULT_BOLD
                }
                else -> {
                    if (k.isLetter && shiftState > 0) label = label.uppercase()
                    textPaint.textSize = sp(if (k.isLetter) 22f else 20f)
                }
            }
            if (k.code == Codes.EMOJI) drawSmiley(c, rect.centerX(), rect.centerY(), dp(10.5f), t.text)
            else c.drawText(label, rect.centerX(), rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
            if (showHints && k.hint.isNotEmpty()) {
                hintPaint.textSize = sp(10f)
                hintPaint.color = t.textMuted
                c.drawText(k.hint, rect.right - dp(7f), rect.top + dp(12f), hintPaint)
            }
        }
        if (oneHand > 0 && !emojiMode) drawOneHandPanel(c, t)
        // swipe-delete indicator
        if (mode == Mode.DELSWIPE && delSteps > 0) {
            val k = downKey
            if (k != null) {
                textPaint.textSize = sp(12f)
                textPaint.color = t.accent2
                textPaint.typeface = Typeface.DEFAULT_BOLD
                c.drawText("−$delSteps palabra" + (if (delSteps > 1) "s" else ""), k.cx - unitW * 1.2f, k.top - dp(4f), textPaint)
            }
        }
    }

    private fun drawTrail(c: Canvas, now: Long): Boolean {
        val particles = renderer.drawParticles(c)
        if (gCount < 2) return particles
        val alpha: Int = when {
            mode == Mode.GLIDE -> 230
            trailFadeStart > 0 -> {
                val e = now - trailFadeStart
                if (e >= 300) { gCount = 0; trailFadeStart = 0; return particles }
                (230 * (1f - e / 300f)).toInt()
            }
            else -> return particles
        }
        renderer.drawTrail(c, trailStyle, theme, gx, gy, gCount, alpha)
        return true
    }

    private fun drawPreview(c: Canvas) {
        if (mode != Mode.PRESS || !showPreview) return
        val k = curKey ?: return
        if (k.isSpecial) return
        val t = theme
        val pw = unitW * 1.25f
        val ph = min(rowH * 0.95f, k.top - dp(2f))
        rect.set(k.cx - pw / 2f, k.top - ph + dp(2f), k.cx + pw / 2f, k.top + dp(6f))
        if (rect.left < 0f) rect.offset(-rect.left, 0f)
        if (rect.right > width) rect.offset(width - rect.right, 0f)
        keyPaint.shader = null
        keyPaint.color = darker(t.keyPressed)
        rect.offset(0f, dp(1.5f))
        c.drawRoundRect(rect, dp(9f), dp(9f), keyPaint)
        rect.offset(0f, -dp(1.5f))
        keyPaint.color = t.keyPressed
        c.drawRoundRect(rect, dp(9f), dp(9f), keyPaint)
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = sp(28f)
        textPaint.color = t.text
        val label = if (k.isLetter && shiftState > 0) k.label.uppercase() else k.label
        c.drawText(label, rect.centerX(), rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    private fun drawGlideBubble(c: Canvas) {
        if (mode != Mode.GLIDE || glidePreview.isEmpty() || gCount < 1) return
        val t = theme
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = sp(17f)
        val tw = textPaint.measureText(glidePreview)
        val bw = tw + dp(24f)
        val bh = dp(34f)
        val fx = gx[gCount - 1]
        val fy = gy[gCount - 1]
        var left = (fx - bw / 2f).coerceIn(dp(4f), width - bw - dp(4f))
        var top = fy - dp(70f) - bh / 2f
        if (top < dp(2f)) top = dp(2f)
        rect.set(left, top, left + bw, top + bh)
        keyPaint.shader = null
        keyPaint.color = withAlpha(Color.BLACK, 90)
        rect.offset(0f, dp(2f))
        c.drawRoundRect(rect, bh / 2f, bh / 2f, keyPaint)
        rect.offset(0f, -dp(2f))
        keyPaint.color = t.accent
        c.drawRoundRect(rect, bh / 2f, bh / 2f, keyPaint)
        textPaint.color = Color.WHITE
        c.drawText(glidePreview, rect.centerX(), rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        textPaint.typeface = Typeface.DEFAULT
        left += 0f
    }

    private fun drawPopup(c: Canvas) {
        if (mode != Mode.POPUP || popupAlts.isEmpty()) return
        val t = theme
        val total = popupCellW * popupAlts.size
        rect.set(popupLeft, popupTop, popupLeft + total, popupTop + popupH)
        keyPaint.shader = null
        keyPaint.color = darker(t.keyPressed)
        rect.offset(0f, dp(2f))
        c.drawRoundRect(rect, dp(10f), dp(10f), keyPaint)
        rect.offset(0f, -dp(2f))
        keyPaint.color = t.keyPressed
        c.drawRoundRect(rect, dp(10f), dp(10f), keyPaint)
        popupAlts.forEachIndexed { i, s ->
            val x0 = popupLeft + i * popupCellW
            if (i == popupSel) {
                keyPaint.color = t.accent
                rect.set(x0 + dp(3f), popupTop + dp(3f), x0 + popupCellW - dp(3f), popupTop + popupH - dp(3f))
                c.drawRoundRect(rect, dp(8f), dp(8f), keyPaint)
            }
            textPaint.typeface = Typeface.DEFAULT
            textPaint.textSize = sp(if (s.length > 2) 18f else 22f)
            textPaint.color = if (i == popupSel) Color.WHITE else t.text
            c.drawText(s, x0 + popupCellW / 2f, popupTop + popupH / 2f - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        }
    }

    private fun drawFloats(c: Canvas, now: Long): Boolean {
        if (floats.isEmpty()) return false
        val it = floats.iterator()
        textPaint.typeface = Typeface.DEFAULT_BOLD
        while (it.hasNext()) {
            val f = it.next()
            val e = (now - f.start) / 900f
            if (e >= 1f) { it.remove(); continue }
            textPaint.textSize = sp(15f + 4f * (1f - e))
            textPaint.color = withAlpha(f.color, (255 * (1f - e * e)).toInt())
            c.drawText(f.text, f.x, f.y - dp(46f) * e, textPaint)
        }
        return floats.isNotEmpty()
    }

    private fun burst(bx: Float, by: Float) {
        particles.clear()
        particlesStart = SystemClock.uptimeMillis()
        repeat(40) {
            val hue = Random.nextFloat() * 360f
            particles.add(
                Particle(
                    x = bx,
                    y = by,
                    vx = (Random.nextFloat() - 0.5f) * dp(7f),
                    vy = -Random.nextFloat() * dp(5f) - dp(1f),
                    color = Color.HSVToColor(floatArrayOf(hue, 0.8f, 1f)),
                    size = dp(3f + Random.nextFloat() * 4f),
                    rot = Random.nextFloat() * 90f
                )
            )
        }
    }

    private fun drawParticles(c: Canvas, now: Long): Boolean {
        if (particles.isEmpty()) return false
        val e = now - particlesStart
        if (e > 2200) { particles.clear(); return false }
        val a = (255 * (1f - e / 2200f)).toInt().coerceIn(0, 255)
        for (p in particles) {
            p.x += p.vx
            p.y += p.vy
            p.vy += dp(0.35f)
            keyPaint.color = withAlpha(p.color, a)
            c.save()
            c.rotate(p.rot + e / 4f, p.x, p.y)
            c.drawRect(p.x - p.size / 2f, p.y - p.size / 3f, p.x + p.size / 2f, p.y + p.size / 3f, keyPaint)
            c.restore()
        }
        return true
    }

    private fun drawBanner(c: Canvas, now: Long): Boolean {
        if (banners.isEmpty()) return false
        val b = banners[0]
        val e = now - bannerStart
        if (e > bannerDuration) {
            banners.removeAt(0)
            bannerStart = now
            return banners.isNotEmpty()
        }
        val t = theme
        val inT = min(1f, e / 220f)
        val outT = min(1f, (bannerDuration - e) / 260f)
        val k = min(inT, outT)
        val h = topH - dp(4f)
        val y0 = -h + (h + dp(2f)) * k
        rect.set(dp(6f), y0, width - dp(6f), y0 + h)
        keyPaint.shader = LinearGradient(rect.left, 0f, rect.right, 0f, t.accent, t.accent2, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, dp(12f), dp(12f), keyPaint)
        keyPaint.shader = null
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.color = Color.WHITE
        textPaint.textSize = sp(if (b.big) 17f else 14f)
        c.drawText(b.title, rect.centerX(), rect.top + h * 0.45f, textPaint)
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = sp(11.5f)
        textPaint.color = withAlpha(Color.WHITE, 230)
        c.drawText(b.subtitle, rect.centerX(), rect.top + h * 0.82f, textPaint)
        return true
    }

    /** Outline smiley drawn with lines, so it uses the theme's text color (white on dark themes). */
    private fun drawSmiley(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        linePaint.shader = null
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = dp(1.8f)
        linePaint.strokeCap = Paint.Cap.ROUND
        linePaint.color = color
        c.drawCircle(cx, cy, r, linePaint)
        rect.set(cx - r * 0.55f, cy - r * 0.45f, cx + r * 0.55f, cy + r * 0.55f)
        c.drawArc(rect, 25f, 130f, false, linePaint)
        linePaint.style = Paint.Style.FILL
        c.drawCircle(cx - r * 0.35f, cy - r * 0.25f, dp(1.4f), linePaint)
        c.drawCircle(cx + r * 0.35f, cy - r * 0.25f, dp(1.4f), linePaint)
    }

    private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private fun darker(color: Int): Int {
        val r = (Color.red(color) * 0.6f).toInt()
        val g = (Color.green(color) * 0.6f).toInt()
        val b = (Color.blue(color) * 0.6f).toInt()
        return Color.argb(Color.alpha(color), r, g, b)
    }

    // ================================================================== touch

    private val longPress = Runnable {
        val k = downKey
        if (k != null && mode == Mode.PRESS) {
            if (k.code == Codes.SPACE) {
                mode = Mode.NONE
                listener.onSpaceLongPress()
            } else if (k.alts.isNotEmpty()) {
                openPopup(k)
                listener.onPressFeedback(0)
            }
            invalidate()
        }
    }

    private val repeatDel = object : Runnable {
        override fun run() {
            if (mode != Mode.PRESS || downKey?.code != Codes.DELETE) return
            delRepeated = true
            listener.onDeleteRepeat()
            postDelayed(this, 55)
        }
    }

    private fun openPopup(k: Key) {
        var alts = k.alts.map { if (shiftState > 0 && it.length == 1 && it[0].isLetter()) it.uppercase() else it }
        val rightSide = k.cx > width / 2f
        if (rightSide) alts = alts.reversed()
        popupAlts = alts
        popupCellW = min(unitW, (width - padX * 2) / alts.size)
        popupH = min(rowH * 0.95f, k.top - dp(2f))
        popupTop = k.top - popupH + dp(2f)
        val total = popupCellW * alts.size
        popupLeft = if (rightSide) k.cx + popupCellW / 2f - total else k.cx - popupCellW / 2f
        popupLeft = popupLeft.coerceIn(padX, max(padX, width - padX - total))
        mode = Mode.POPUP
        updatePopupSel(downX)
    }

    private fun updatePopupSel(x: Float) {
        if (popupAlts.isEmpty()) return
        popupSel = ((x - popupLeft) / popupCellW).toInt().coerceIn(0, popupAlts.size - 1)
    }

    private fun keyAt(x: Float, y: Float): Key? {
        if (y < topH) return null
        if (!emojiMode && layout === letters) {
            // adaptive hit areas: special keys keep their rectangles; letters go to the
            // nearest *learned* center (where you usually press that key)
            for (k in layout.allKeys) if (!k.isLetter && k.contains(x, y)) return k
            var bestL: Key? = null
            var bdL = Float.MAX_VALUE
            for (k in layout.allKeys) {
                if (!k.isLetter) continue
                val o = TouchModel.offset(k.label[0])
                val cx = k.cx + (o?.get(0) ?: 0f) * unitW
                val cy = k.cy + (o?.get(1) ?: 0f) * rowH
                val ddx = (x - cx) / unitW
                val ddy = (y - cy) / rowH
                val d = ddx * ddx + ddy * ddy
                if (d < bdL) { bdL = d; bestL = k }
            }
            if (bestL != null && bdL < 1.2f) return bestL
        }
        var best: Key? = null
        var bd = Float.MAX_VALUE
        for (k in (if (emojiMode) emojiBar else layout.allKeys)) {
            if (k.contains(x, y)) return k
            val d = hypot(k.cx - x, (k.cy - y) * 0.8f)
            if (d < bd) { bd = d; best = k }
        }
        return best
    }

    /**
     * What's under the finger on the first row: 0..2 suggestion, -3 microphone, -6 toolbar button,
     * -7 paste chip, -10-i toolbar icon i, -1 nothing (the XP row has no action).
     */
    private fun stripIndexAt(x: Float, y: Float): Int {
        if (y >= sugH) return -1
        if (removeAsk != null) {
            val cancelL = width - dp(46f)
            return when {
                x >= cancelL -> -21
                x >= cancelL - dp(92f) -> -20
                else -> -22               // tap on the question: also cancels
            }
        }
        if (x >= width - sideW) return -3
        if (x < sideW) return -6
        return when (stripContent()) {
            1 -> -10 - ((x - sideW) / toolCellW()).toInt().coerceIn(0, toolCount - 1)
            2 -> -7
            else -> {
                val i = ((x - sideW) / ((width - sideW * 2) / 3f)).toInt().coerceIn(0, 2)
                if (suggestions[i].isNotEmpty()) i else -1
            }
        }
    }

    private fun addGlide(x: Float, y: Float) {
        if (gCount >= gx.size) {
            gx = gx.copyOf(gx.size * 2)
            gy = gy.copyOf(gy.size * 2)
        }
        gx[gCount] = x
        gy[gCount] = y
        gCount++
        if (mode == Mode.GLIDE) renderer.onTrailPoint(trailStyle, theme, x, y)
    }

    private fun cancelGesture() {
        removeCallbacks(longPress)
        removeCallbacks(repeatDel)
        removeCallbacks(editRepeat)
        removeCallbacks(sugLongPress)
        editPressed = null
        mode = Mode.NONE
        downKey = null
        curKey = null
        popupAlts = emptyList()
        activeId = -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        try {
            handleTouch(e)
        } catch (ex: Exception) {
            CrashLog.log(ex, "toque")
            cancelGesture()
            gCount = 0
            invalidate()
        }
        return true
    }

    private fun handleTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeId = e.getPointerId(0)
                start(e.getX(0), e.getY(0))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // fast two-thumb typing: finish the previous tap and start the new one
                if (mode == Mode.PRESS) {
                    val k = curKey
                    removeCallbacks(longPress)
                    removeCallbacks(repeatDel)
                    if (k != null && !(k.code == Codes.DELETE && delRepeated)) { lastTapX = downX; lastTapY = downY; listener.onKey(k) }
                    val i = e.actionIndex
                    activeId = e.getPointerId(i)
                    start(e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(activeId)
                if (i >= 0) {
                    if (mode == Mode.GLIDE) {
                        for (h in 0 until e.historySize) addGlide(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                    }
                    move(e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                if (e.getPointerId(i) == activeId) {
                    up(e.getX(i), e.getY(i))
                    activeId = -1
                }
            }
            MotionEvent.ACTION_UP -> {
                val i = e.actionIndex
                if (e.getPointerId(i) == activeId) up(e.getX(i), e.getY(i))
                activeId = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.GLIDE && gCount > 1) listener.onGlide(gx.copyOf(gCount), gy.copyOf(gCount), gCount)
                glidePreview = ""
                cancelGesture()
                gCount = 0
                invalidate()
            }
        }
    }

    private fun start(x: Float, y: Float) {
        removeCallbacks(longPress)
        removeCallbacks(repeatDel)
        downX = x
        downY = y
        if (banners.isNotEmpty() && y < topH) {
            // tap on banner dismisses it
            banners.removeAt(0)
            bannerStart = SystemClock.uptimeMillis()
            mode = Mode.NONE
            invalidate()
            return
        }
        if (clipMode && y >= topH) {
            mode = Mode.CLIP
            clipScrolled = false
            lastMoveY = y
            return
        }
        if (voiceMode && y >= topH) {
            // tap anywhere on the voice panel to finish
            mode = Mode.NONE
            listener.onMic()
            return
        }
        if (editMode && y >= topH) {
            val cell = editCellAt(x, y)
            mode = Mode.EDIT
            editPressed = cell
            if (cell != null) {
                listener.onPressFeedback(0)
                if (cell.repeats) {
                    fireEdit(cell)
                    postDelayed(editRepeat, 400)
                }
            }
            invalidate()
            return
        }
        if (!emojiMode && !clipMode) {
            val hit = oneHandHit(x, y)
            if (hit != 0) {
                mode = Mode.NONE
                if (hit > 0) {
                    listener.onPressFeedback(0)
                    oneHand = if (hit == 1) 0 else (if (oneHand == 1) 2 else 1)
                    if (oneHand > 0) oneHandSide = oneHand
                    listener.onOneHand(oneHand)
                }
                return
            }
        }
        if (y < topH) {
            mode = Mode.STRIP
            stripPressed = stripIndexAt(x, y)
            removeCallbacks(sugLongPress)
            if (stripPressed in 0..2) postDelayed(sugLongPress, 550)
            invalidate()
            return
        }
        if (emojiMode && y < emojiBar[0].top) {
            if (y < emojiGridTop) {
                // category tab
                val tabs = max(1, emojiCats.size)
                val i = (x / (width / tabs.toFloat())).toInt().coerceIn(0, tabs - 1)
                if (i != emojiCat) {
                    emojiCat = i
                    emojiScroll = 0f
                    emojiVel = 0f
                    listener.onPressFeedback(0)
                }
                mode = Mode.NONE
                invalidate()
                return
            }
            mode = Mode.EMOJI
            emojiVel = 0f
            emojiScrolled = false
            lastMoveY = y
            lastMoveT = SystemClock.uptimeMillis()
            removeCallbacks(emojiLongPress)
            postDelayed(emojiLongPress, 380)
            return
        }
        val k = keyAt(x, y)
        if (k == null) { mode = Mode.NONE; return }
        downKey = k
        curKey = k
        mode = Mode.PRESS
        trailFadeStart = 0
        gCount = 0
        addGlide(x, y)
        listener.onPressFeedback(k.code)
        when {
            k.code == Codes.DELETE -> {
                delRepeated = false
                delSteps = 0
                postDelayed(repeatDel, 400)
            }
            k.code == Codes.SPACE -> postDelayed(longPress, 600)
            k.alts.isNotEmpty() -> postDelayed(longPress, longPressMs)
        }
        invalidate()
    }

    private fun move(x: Float, y: Float) {
        when (mode) {
            Mode.PRESS -> {
                val k = downKey ?: return
                val dx = x - downX
                val dy = y - downY
                addGlide(x, y)
                if (k.code == Codes.SPACE && abs(dx) > unitW * 0.6f) {
                    removeCallbacks(longPress)
                    mode = Mode.CURSOR
                    lastCursorX = x
                } else if (k.code == Codes.DELETE && !delRepeated && dx < -unitW * 0.8f) {
                    removeCallbacks(repeatDel)
                    mode = Mode.DELSWIPE
                    delSteps = 0
                } else if (glideEnabled && layout === letters && k.isLetter && hypot(dx, dy) > unitW * 0.55f) {
                    removeCallbacks(longPress)
                    mode = Mode.GLIDE
                    glidePreview = ""
                    lastGlideProgress = SystemClock.uptimeMillis()
                    listener.onGlideStart()
                } else if (!k.isSpecial) {
                    val nk = keyAt(x, y)
                    if (nk != null && !nk.isSpecial && nk !== curKey) {
                        curKey = nk
                        removeCallbacks(longPress)
                        if (nk.alts.isNotEmpty()) postDelayed(longPress, longPressMs)
                        downKey = nk
                        invalidate()
                    }
                }
            }
            Mode.POPUP -> {
                val old = popupSel
                updatePopupSel(x)
                if (old != popupSel) invalidate()
            }
            Mode.GLIDE -> {
                addGlide(x, y)
                val now = SystemClock.uptimeMillis()
                if (now - lastGlideProgress >= 60) {
                    lastGlideProgress = now
                    listener.onGlideProgress(gx.copyOf(gCount), gy.copyOf(gCount), gCount)
                }
                invalidate()
            }
            Mode.CURSOR -> {
                val step = unitW * 0.45f
                while (x - lastCursorX > step) { listener.onCursor(1); lastCursorX += step }
                while (lastCursorX - x > step) { listener.onCursor(-1); lastCursorX -= step }
            }
            Mode.DELSWIPE -> {
                val s = max(0, ((downX - x) / (unitW * 1.1f)).toInt())
                if (s != delSteps) {
                    delSteps = s
                    listener.onPressFeedback(0)
                    invalidate()
                }
            }
            Mode.STRIP -> {
                if (abs(y - downY) > topH || abs(x - downX) > dp(24f)) {
                    if (stripPressed != -1) {
                        stripPressed = -1
                        removeCallbacks(sugLongPress)
                        invalidate()
                    }
                }
            }
            Mode.EMOJI -> {
                if (abs(y - downY) > dp(8f)) { emojiScrolled = true; removeCallbacks(emojiLongPress) }
                if (emojiScrolled) {
                    val now = SystemClock.uptimeMillis()
                    val dy = y - lastMoveY
                    emojiScroll = (emojiScroll - dy).coerceIn(0f, emojiMaxScroll())
                    val dt = max(1L, now - lastMoveT)
                    emojiVel = -dy / dt
                    lastMoveY = y
                    lastMoveT = now
                    invalidate()
                }
            }
            Mode.CLIP -> {
                if (abs(y - downY) > dp(8f)) clipScrolled = true
                if (clipScrolled) {
                    clipScroll = (clipScroll - (y - lastMoveY)).coerceIn(0f, clipMaxScroll())
                    lastMoveY = y
                    invalidate()
                }
            }
            Mode.EDIT -> {
                // finger slid off the button: stop repeating
                val cell = editPressed
                if (cell != null && editCellAt(x, y) !== cell) {
                    removeCallbacks(editRepeat)
                    editPressed = null
                    invalidate()
                }
            }
            Mode.NONE -> {}
        }
    }

    private fun up(x: Float, y: Float) {
        removeCallbacks(sugLongPress)
        removeCallbacks(emojiLongPress)
        removeCallbacks(longPress)
        removeCallbacks(repeatDel)
        when (mode) {
            Mode.PRESS -> {
                val k = curKey
                if (k != null && !(k.code == Codes.DELETE && delRepeated)) { lastTapX = downX; lastTapY = downY; listener.onKey(k) }
                gCount = 0
            }
            Mode.POPUP -> {
                if (popupSel in popupAlts.indices) {
                    listener.onAltText(popupAlts[popupSel])
                    if (emojiMode) addRecent(popupAlts[popupSel])
                }
                gCount = 0
            }
            Mode.GLIDE -> {
                addGlide(x, y)
                listener.onGlide(gx.copyOf(gCount), gy.copyOf(gCount), gCount)
                trailFadeStart = SystemClock.uptimeMillis()
                glidePreview = ""
            }
            Mode.DELSWIPE -> if (delSteps > 0) listener.onDeleteWords(delSteps)
            Mode.STRIP -> {
                val i = stripIndexAt(x, y)
                if (i == stripPressed && i != -1) {
                    listener.onPressFeedback(0)
                    when (i) {
                        -20 -> {
                            val wd = removeAsk
                            removeAsk = null
                            if (wd != null) listener.onSuggestionRemove(wd)
                        }
                        -21, -22 -> removeAsk = null
                        -3 -> listener.onMic()
                        -6 -> toolbarManual = stripContent() != 1
                        -7 -> listener.onClipChip()
                        -10 -> if (clipMode) listener.onKey(Key("ABC", Codes.LETTERS)) else listener.onClipboard()
                        -11 -> if (editMode) listener.onKey(Key("ABC", Codes.LETTERS)) else showEditPanel()
                        -12 -> {
                            oneHand = if (oneHand == 0) oneHandSide else 0
                            listener.onOneHand(oneHand)
                        }
                        -13 -> listener.onSettings()
                        else -> if (i >= 0) listener.onSuggestion(i)
                    }
                }
                stripPressed = -1
            }
            Mode.EDIT -> {
                removeCallbacks(editRepeat)
                val cell = editPressed
                if (cell != null && !cell.repeats && editCellAt(x, y) === cell) fireEdit(cell)
                editPressed = null
            }
            Mode.EMOJI -> {
                if (!emojiScrolled) {
                    val e = emojiAt(downX, downY)
                    if (e != null) {
                        listener.onPressFeedback(0)
                        listener.onAltText(e)
                        addRecent(e)
                    }
                    emojiVel = 0f
                } else if (SystemClock.uptimeMillis() - lastMoveT > 80) {
                    emojiVel = 0f
                }
            }
            Mode.CLIP -> if (!clipScrolled) clipTap(downX, downY)
            Mode.CURSOR, Mode.NONE -> {}
        }
        mode = Mode.NONE
        downKey = null
        curKey = null
        popupAlts = emptyList()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(longPress)
        removeCallbacks(repeatDel)
        removeCallbacks(editRepeat)
        removeCallbacks(emojiLongPress)
    }
}
