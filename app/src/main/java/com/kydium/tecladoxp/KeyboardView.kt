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
        fun onPressFeedback()
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
    var longPressMs = 320L
    var xpStyle = "clasica"
        set(v) { field = v; invalidate() }
    var trailStyle = "clasica"
    val renderer = StyleRenderer(resources.displayMetrics)

    private val letters = Layouts.letters()
    private val symbols = Layouts.symbols()
    private val symbols2 = Layouts.symbols2()
    var layout: KbLayout = letters
        private set

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
    private val rect = RectF()

    // ------------------------------------------------------------------ touch state
    private enum class Mode { NONE, PRESS, POPUP, GLIDE, CURSOR, DELSWIPE, STRIP, EMOJI }

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
        emojiMode = false
        voiceMode = false
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
                lines.forEach { line ->
                    val parts = line.split('\t')
                    if (parts.size > 1) cats.add(Pair(parts[0], parts.drop(1).filter { it.isNotEmpty() }))
                }
            }
        } catch (_: Exception) {
        }
        val saved = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("recent_emojis", "") ?: ""
        recents.clear()
        recents.addAll(saved.split('\t').filter { it.isNotEmpty() })
        emojiCats = cats
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
        invalidate()
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
    fun letterCenters(): Map<Char, FloatArray> {
        val m = HashMap<Char, FloatArray>()
        for (k in letters.allKeys) if (k.isLetter) m[k.label[0]] = floatArrayOf(k.cx, k.cy)
        return m
    }

    // ================================================================== layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (topH + rowH * 4 + padY * 2).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bgShader = null
        unitW = (w - padX * 2) / 10f
        for (l in listOf(letters, symbols, symbols2)) layoutKeys(l, w)
        // emoji bottom bar (same height as a key row, at the bottom)
        var x = padX
        val barTop = h - padY - rowH * 0.9f
        for (k in emojiBar) {
            k.left = x
            k.right = x + k.width * unitW
            k.top = barTop
            k.bottom = h - padY
            x = k.right
        }
    }

    private fun layoutKeys(l: KbLayout, w: Int) {
        l.rows.forEachIndexed { r, row ->
            val units = row.sumOf { it.width.toDouble() }.toFloat()
            var x = (w - units * unitW) / 2f
            val top = topH + padY + r * rowH
            for (k in row) {
                k.left = x
                k.right = x + k.width * unitW
                k.top = top
                k.bottom = top + rowH
                x = k.right
            }
        }
    }

    // ================================================================== drawing

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val now = SystemClock.uptimeMillis()
        val w = width.toFloat()

        if (bgShader == null) bgShader = LinearGradient(0f, 0f, 0f, height.toFloat(), t.bgTop, t.bgBottom, Shader.TileMode.CLAMP)
        bgPaint.shader = bgShader
        canvas.drawRect(0f, 0f, w, height.toFloat(), bgPaint)

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

        // ---- row 1: suggestions + mic button at the right
        val micW = dp(44f)
        drawMicButton(c, w - micW / 2f, sugH / 2f, t)
        val slotW = (w - micW) / 3f
        val textY = sugH / 2f + dp(1f)
        for (i in 0..2) {
            val s = suggestions[i]
            val x0 = slotW * i
            if (mode == Mode.STRIP && stripPressed == i && s.isNotEmpty()) {
                keyPaint.shader = null
                keyPaint.color = withAlpha(t.keyPressed, 160)
                rect.set(x0 + dp(2f), dp(2f), x0 + slotW - dp(2f), sugH - dp(1f))
                c.drawRoundRect(rect, dp(8f), dp(8f), keyPaint)
            }
            if (i > 0 && suggestions.any { it.isNotEmpty() }) {
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
        if (combo >= 5 && now < comboExpire) {
            val label = "⚡x" + String.format("%.1f", XpManager.comboMultiplier(combo))
            val pulse = 1f + 0.08f * sin(now / 120.0).toFloat()
            textPaint.textSize = sp(11f) * pulse
            textPaint.color = t.accent
            textPaint.textAlign = Paint.Align.RIGHT
            c.drawText(label, textR, ty, textPaint)
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
        val gapX = dp(3f)
        val gapY = dp(4.5f)
        val radius = dp(7f)
        val pressed = if (mode == Mode.PRESS || mode == Mode.POPUP || mode == Mode.CURSOR || mode == Mode.DELSWIPE) curKey else null
        var idx = 0
        if (voiceMode) {
            drawVoicePanel(c, t)
            return
        }
        if (emojiMode) drawEmojiPanel(c)
        val keys = if (emojiMode) emojiBar else layout.allKeys
        for (k in keys) {
            idx++
            val special = k.isSpecial && k.code != Codes.SPACE
            val base = if (special) t.keySpecial else t.key
            rect.set(k.left + gapX / 2f, k.top + gapY / 2f, k.right - gapX / 2f, k.bottom - gapY / 2f)
            // shadow
            keyPaint.shader = null
            keyPaint.color = darker(base)
            rect.offset(0f, dp(1.2f))
            c.drawRoundRect(rect, radius, radius, keyPaint)
            rect.offset(0f, -dp(1.2f))
            keyPaint.color = if (k === pressed) t.keyPressed else base
            if (k.code == Codes.ENTER) keyPaint.color = if (k === pressed) t.keyPressed else t.accent
            if (k.code == Codes.SHIFT && shiftState > 0) keyPaint.color = if (shiftState == 2) t.accent else t.keyPressed
            c.drawRoundRect(rect, radius, radius, keyPaint)
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
        if (mode != Mode.PRESS) return
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
                listener.onPressFeedback()
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
        var best: Key? = null
        var bd = Float.MAX_VALUE
        for (k in (if (emojiMode) emojiBar else layout.allKeys)) {
            if (k.contains(x, y)) return k
            val d = hypot(k.cx - x, (k.cy - y) * 0.8f)
            if (d < bd) { bd = d; best = k }
        }
        return best
    }

    private fun stripIndexAt(x: Float, y: Float): Int {
        if (y >= sugH) return -2   // xp row opens the profile
        val micW = dp(44f)
        if (x >= width - micW) return -3   // microphone
        val i = (x / ((width - micW) / 3f)).toInt().coerceIn(0, 2)
        return if (suggestions[i].isNotEmpty()) i else -1
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
        mode = Mode.NONE
        downKey = null
        curKey = null
        popupAlts = emptyList()
        activeId = -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
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
                    if (k != null && !(k.code == Codes.DELETE && delRepeated)) listener.onKey(k)
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
        return true
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
        if (voiceMode && y >= topH) {
            // tap anywhere on the voice panel to finish
            mode = Mode.NONE
            listener.onMic()
            return
        }
        if (y < topH) {
            mode = Mode.STRIP
            stripPressed = stripIndexAt(x, y)
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
                    listener.onPressFeedback()
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
        listener.onPressFeedback()
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
                    listener.onPressFeedback()
                    invalidate()
                }
            }
            Mode.STRIP -> {
                if (abs(y - downY) > topH) { stripPressed = -1; invalidate() }
            }
            Mode.EMOJI -> {
                if (abs(y - downY) > dp(8f)) emojiScrolled = true
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
            Mode.NONE -> {}
        }
    }

    private fun up(x: Float, y: Float) {
        removeCallbacks(longPress)
        removeCallbacks(repeatDel)
        when (mode) {
            Mode.PRESS -> {
                val k = curKey
                if (k != null && !(k.code == Codes.DELETE && delRepeated)) listener.onKey(k)
                gCount = 0
            }
            Mode.POPUP -> {
                if (popupSel in popupAlts.indices) listener.onAltText(popupAlts[popupSel])
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
                if (i == stripPressed) {
                    if (i == -2) listener.onBadge()
                    else if (i == -3) listener.onMic()
                    else if (i >= 0) listener.onSuggestion(i)
                }
                stripPressed = -1
            }
            Mode.EMOJI -> {
                if (!emojiScrolled) {
                    val e = emojiAt(downX, downY)
                    if (e != null) {
                        listener.onPressFeedback()
                        listener.onAltText(e)
                        addRecent(e)
                    }
                    emojiVel = 0f
                } else if (SystemClock.uptimeMillis() - lastMoveT > 80) {
                    emojiVel = 0f
                }
            }
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
    }
}
