package com.kydium.tecladoxp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.TypedValue
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** A cosmetic option that unlocks at a level. */
class StyleOption(val id: String, val name: String, val unlockLevel: Int)

object XpBarStyles {
    val all = listOf(
        StyleOption("clasica", "Clásica", 1),
        StyleOption("club", "Club Campestre", 1),
        StyleOption("bloques", "Bloques", 1),
        StyleOption("minima", "Mínima", 1),
        StyleOption("neon", "Neón", 5),
        StyleOption("pixel", "Pixel retro", 10)
    )

    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: all[0]
}

object TrailStyles {
    val all = listOf(
        StyleOption("clasica", "Clásica", 1),
        StyleOption("cometa", "Cometa", 1),
        StyleOption("neon", "Neón", 1),
        StyleOption("arcoiris", "Arcoíris", 4),
        StyleOption("fuego", "Fuego", 8),
        StyleOption("chispas", "Chispas", 12)
    )

    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: all[0]
}

/**
 * Draws the level badge, the XP bar and the glide trail in every style.
 * Shared by the keyboard and by the previews in the settings screen.
 */
class StyleRenderer(private val dm: DisplayMetrics) {

    fun dp(v: Float) = v * dm.density
    fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val bounds = Rect()
    private val path = Path()

    private val italicBold: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)

    // Club Campestre palette (same as club-xp.js)
    private val clubBlue = Color.parseColor("#2F74CC")

    companion object {
        fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

        fun lerpColor(a: Int, b: Int, t: Float): Int {
            val f = t.coerceIn(0f, 1f)
            return Color.argb(
                (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f).toInt(),
                (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
            )
        }
    }

    /** Draws [s] so that its real glyph box is centered on (cx, cy). */
    private fun drawCentered(c: Canvas, s: String, cx: Float, cy: Float) {
        text.getTextBounds(s, 0, s.length, bounds)
        // textAlign is CENTER: the advance box is centered on x, so correct by the glyph box offset
        c.drawText(s, cx + text.measureText(s) / 2f - bounds.exactCenterX(), cy - bounds.exactCenterY(), text)
    }

    /** Dark navy circle with white ring and bold italic number (GTA RP style). */
    private fun rpCircle(c: Canvas, cx: Float, cy: Float, r: Float, num: String, pressed: Boolean) {
        fill.shader = null
        fill.color = Color.argb(70, 0, 0, 0)
        c.drawCircle(cx, cy + dp(1f), r + dp(1.5f), fill)
        fill.color = if (pressed) Color.parseColor("#1D3E73") else Color.parseColor("#0B1E3F")
        c.drawCircle(cx, cy, r, fill)
        stroke.shader = null
        stroke.strokeWidth = max(dp(1.6f), r * 0.13f)
        stroke.color = Color.WHITE
        c.drawCircle(cx, cy, r - stroke.strokeWidth / 2f, stroke)
        text.typeface = italicBold
        text.textSize = r * (if (num.length >= 3) 1.0f else 1.35f)
        text.color = Color.parseColor("#0A0A0A")
        drawCentered(c, num, cx + dp(0.8f), cy + dp(1f))
        text.color = Color.WHITE
        drawCentered(c, num, cx, cy)
        text.typeface = Typeface.DEFAULT
    }

    // 5x7 pixel font for digits
    private val PIXEL_DIGITS = arrayOf(
        arrayOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        arrayOf(".###.", "#...#", "....#", "..##.", ".#...", "#....", "#####"),
        arrayOf(".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###."),
        arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        arrayOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        arrayOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##..")
    )

    /** Draws digits as square pixels ([px] = size of one pixel) with a 1-pixel outline, centered on (cx, cy). */
    fun drawPixelText(c: Canvas, s: String, cx: Float, cy: Float, px: Float, color: Int, outline: Int) {
        val glyphs = s.filter { it.isDigit() }
        if (glyphs.isEmpty()) return
        val w = glyphs.length * 5 * px + (glyphs.length - 1) * px
        val h = 7 * px
        val x0 = Math.round(cx - w / 2f).toFloat()
        val y0 = Math.round(cy - h / 2f).toFloat()
        fill.shader = null
        val aa = fill.isAntiAlias
        fill.isAntiAlias = false
        for (pass in 0..1) {
            fill.color = if (pass == 0) outline else color
            glyphs.forEachIndexed { gi, ch ->
                val g = PIXEL_DIGITS[ch - '0']
                val gx = x0 + gi * 6 * px
                for (row in 0 until 7) for (col in 0 until 5) {
                    if (g[row][col] != '#') continue
                    val x = gx + col * px
                    val y = y0 + row * px
                    if (pass == 0) c.drawRect(x - px, y - px, x + 2 * px, y + 2 * px, fill)
                    else c.drawRect(x, y, x + px, y + px, fill)
                }
            }
        }
        fill.isAntiAlias = aa
    }

    // ================================================================ badge

    fun drawBadge(c: Canvas, style: String, t: KbTheme, cx: Float, cy: Float, r: Float, level: Int, frac: Float, pressed: Boolean) {
        fill.shader = null
        stroke.shader = null
        val num = level.toString()
        when (style) {
            "bloques" -> {
                if (pressed) {
                    fill.color = withAlpha(Color.WHITE, 40)
                    c.drawRect(cx - r, cy - r, cx + r, cy + r, fill)
                }
                // pixel-art digits, green with a dark outline (block-game style)
                val px = r * (if (num.length >= 3) 0.13f else 0.17f)
                drawPixelText(c, num, cx, cy, px, Color.parseColor("#80FF20"), Color.parseColor("#141414"))
            }
            "club" -> rpCircle(c, cx, cy, r, num, pressed)
            "pixel" -> {
                val s = r * 0.92f
                fill.color = t.accent
                c.drawRect(cx - s, cy - s, cx + s, cy + s, fill)
                fill.color = if (pressed) t.keyPressed else t.keySpecial
                val b = dp(3f)
                c.drawRect(cx - s + b, cy - s + b, cx + s - b, cy + s - b, fill)
                // progress as pixel border on the bottom
                fill.color = t.accent2
                c.drawRect(cx - s, cy + s - b, cx - s + 2 * s * frac, cy + s, fill)
                text.typeface = Typeface.MONOSPACE
                text.isFakeBoldText = true
                text.textSize = sp(if (level >= 100) 12f else 15f)
                text.color = t.text
                drawCentered(c, num, cx, cy)
                text.isFakeBoldText = false
            }
            "neon" -> {
                fill.color = if (pressed) t.keyPressed else t.keySpecial
                c.drawCircle(cx, cy, r, fill)
                for ((w, a) in listOf(9f to 35, 6f to 70, 3f to 255)) {
                    stroke.strokeWidth = dp(w)
                    stroke.color = withAlpha(t.accent, a)
                    rect.set(cx - r, cy - r, cx + r, cy + r)
                    c.drawArc(rect, -90f, 360f * max(frac, 0.02f), false, stroke)
                }
                stroke.strokeWidth = dp(1f)
                stroke.color = withAlpha(t.accent2, 90)
                c.drawCircle(cx, cy, r - dp(4f), stroke)
                text.typeface = Typeface.DEFAULT_BOLD
                text.textSize = sp(if (level >= 100) 12f else 15f)
                text.color = withAlpha(t.accent, 90)
                drawCentered(c, num, cx, cy)
                text.color = t.text
                drawCentered(c, num, cx, cy)
            }
            "minima" -> {
                if (pressed) {
                    fill.color = t.keyPressed
                    c.drawCircle(cx, cy, r, fill)
                }
                text.typeface = Typeface.DEFAULT
                text.textSize = sp(8f)
                text.color = t.textMuted
                text.getTextBounds("NV", 0, 2, bounds)
                val hNv = bounds.height().toFloat()
                text.typeface = Typeface.DEFAULT_BOLD
                text.textSize = sp(if (level >= 100) 15f else 19f)
                text.getTextBounds(num, 0, num.length, bounds)
                val hNum = bounds.height().toFloat()
                val gap = dp(3f)
                val top = cy - (hNv + gap + hNum) / 2f
                text.color = t.accent
                c.drawText(num, cx, top + hNv + gap + hNum, text)
                text.typeface = Typeface.DEFAULT
                text.textSize = sp(8f)
                text.color = t.textMuted
                c.drawText("NV", cx, top + hNv, text)
            }
            else -> { // clasica
                fill.color = if (pressed) t.keyPressed else t.keySpecial
                c.drawCircle(cx, cy, r, fill)
                stroke.strokeWidth = dp(3.2f)
                stroke.color = withAlpha(t.textMuted, 70)
                c.drawCircle(cx, cy, r, stroke)
                stroke.color = t.accent
                rect.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(rect, -90f, 360f * frac, false, stroke)
                // "NV" + number as one block, centered in the circle
                text.typeface = Typeface.DEFAULT
                text.textSize = sp(7.5f)
                text.getTextBounds("NV", 0, 2, bounds)
                val hNv = bounds.height().toFloat()
                text.typeface = Typeface.DEFAULT_BOLD
                text.textSize = sp(if (level >= 100) 12f else 15f)
                text.getTextBounds(num, 0, num.length, bounds)
                val hNum = bounds.height().toFloat()
                val gap = dp(2.5f)
                val top = cy - (hNv + gap + hNum) / 2f
                text.color = t.text
                c.drawText(num, cx, top + hNv + gap + hNum, text)
                text.typeface = Typeface.DEFAULT
                text.textSize = sp(7.5f)
                text.color = t.textMuted
                c.drawText("NV", cx, top + hNv, text)
            }
        }
        text.typeface = Typeface.DEFAULT
    }

    // ================================================================ bar

    /**
     * Bar along [left, right], vertically centered on [cy].
     * [whiteTo] > [frac] paints the just-earned part in white (it then fills with color).
     */
    fun drawBar(
        c: Canvas, style: String, t: KbTheme, left: Float, right: Float, cy: Float, frac: Float, level: Int,
        whiteTo: Float = frac, whiteAlpha: Int = 255, circleR: Float = dp(11f), circleCy: Float = cy
    ) {
        drawBarInner(c, style, t, left, right, cy, frac, level, whiteTo, whiteAlpha, circleR, circleCy)
        val f = frac.coerceIn(0f, 1f)
        if (style != "club" && style != "bloques" && whiteTo > f) {
            // white "just earned" part for the other styles
            val hh = if (style == "minima") dp(1f) else dp(2.6f)
            fill.shader = null
            fill.color = withAlpha(Color.WHITE, whiteAlpha)
            rect.set(left + (right - left) * f, cy - hh, left + (right - left) * whiteTo.coerceAtMost(1f), cy + hh)
            c.drawRoundRect(rect, hh, hh, fill)
        }
    }

    private fun drawBarInner(
        c: Canvas, style: String, t: KbTheme, left: Float, right: Float, cy: Float, frac: Float, level: Int,
        whiteTo: Float, whiteAlpha: Int, circleR: Float, circleCy: Float
    ) {
        fill.shader = null
        val f = frac.coerceIn(0f, 1f)
        when (style) {
            "bloques" -> {
                // pixel-art bar: square blocks, 4 rows of shading, rounded (stair-step) corners
                val px = dp(1.8f)
                val rows = 4
                val cols = ((right - left) / px).toInt() - 2
                val gw = cols + 2
                val gh = rows + 2
                val x0 = Math.round(left + ((right - left) - gw * px) / 2f).toFloat()
                val top = Math.round(cy - gh * px / 2f).toFloat()
                val aa = fill.isAntiAlias
                fill.isAntiAlias = false
                val full = intArrayOf(0xFFC6FF7A.toInt(), 0xFF80FF20.toInt(), 0xFF5DB31A.toInt(), 0xFF2F6B0B.toInt())
                val fullSep = intArrayOf(0xFF5FB61A.toInt(), 0xFF3F8A10.toInt(), 0xFF2F6B0B.toInt(), 0xFF1F4A07.toInt())
                val empty = intArrayOf(0xFF4E5A44.toInt(), 0xFF2E3828.toInt(), 0xFF222A1D.toInt(), 0xFF141A11.toInt())
                val emptySep = intArrayOf(0xFF161C12.toInt(), 0xFF0E120B.toInt(), 0xFF0A0D08.toInt(), 0xFF070906.toInt())
                val white = intArrayOf(0xFFFFFFFF.toInt(), 0xFFEFFFE0.toInt(), 0xFFD8F7C0.toInt(), 0xFFA8D48A.toInt())
                val filledCols = (cols * f).toInt()
                val whiteCols = (cols * whiteTo.coerceIn(f, 1f)).toInt()
                val cellW = cols / 18f
                // corner radius in pixels (cuts a stair-step)
                fun inShape(gx: Int, gy: Int): Boolean {
                    if (gx < 0 || gy < 0 || gx >= gw || gy >= gh) return false
                    val dx = min(gx, gw - 1 - gx)
                    val dy = min(gy, gh - 1 - gy)
                    return dx + dy >= 2
                }
                for (gy in 0 until gh) for (gx in 0 until gw) {
                    if (!inShape(gx, gy)) continue
                    val interior = inShape(gx - 1, gy) && inShape(gx + 1, gy) && inShape(gx, gy - 1) && inShape(gx, gy + 1)
                    fill.color = if (!interior) {
                        Color.parseColor("#0A0A0A")
                    } else {
                        val i = gx - 1
                        val row = (gy - 1).coerceIn(0, rows - 1)
                        val sep = i > 0 && (i / cellW).toInt() != ((i - 1) / cellW).toInt()
                        val pal = when {
                            i < filledCols -> if (sep) fullSep else full
                            i < whiteCols && whiteAlpha >= 128 -> white
                            else -> if (sep) emptySep else empty
                        }
                        pal[row]
                    }
                    val x = x0 + gx * px
                    val y = top + gy * px
                    c.drawRect(x, y, x + px, y + px, fill)
                }
                fill.isAntiAlias = aa
            }
            "club" -> {
                // GTA-style RP bar: 10 separate dashes between the two level circles
                val nr = circleR
                val ncx = right - nr
                val bl = left + dp(2f)
                val br = ncx - nr - dp(7f)
                val h = dp(5f)
                val gap = dp(3f)
                val segW = (br - bl - gap * 9) / 10f
                val total = br - bl
                val white = whiteTo.coerceIn(f, 1f)
                for (i in 0 until 10) {
                    val x0 = bl + i * (segW + gap)
                    val x1 = x0 + segW
                    fill.color = Color.argb(150, 11, 30, 63)
                    c.drawRect(x0, cy - h / 2f, x1, cy + h / 2f, fill)
                    // segment positions in "bar fraction" space ignore gaps visually but map linearly
                    val s0 = (x0 - bl) / total
                    val s1 = (x1 - bl) / total
                    val blueEnd = min(s1, f)
                    if (blueEnd > s0) {
                        fill.color = clubBlue
                        c.drawRect(x0, cy - h / 2f, bl + blueEnd * total, cy + h / 2f, fill)
                    }
                    val wStart = max(s0, f)
                    val wEnd = min(s1, white)
                    if (wEnd > wStart) {
                        fill.color = withAlpha(Color.WHITE, whiteAlpha)
                        c.drawRect(bl + wStart * total, cy - h / 2f, bl + wEnd * total, cy + h / 2f, fill)
                    }
                }
                rpCircle(c, ncx, circleCy, nr, (level + 1).toString(), false)
            }
            "pixel" -> {
                val blocks = 20
                val gap = dp(1.5f)
                val h = dp(5f)
                val bw = (right - left - gap * (blocks - 1)) / blocks
                val filled = (f * blocks).toInt()
                for (i in 0 until blocks) {
                    val x = left + i * (bw + gap)
                    fill.color = when {
                        i < filled -> if (i % 2 == 0) t.accent else t.accent2
                        else -> withAlpha(t.textMuted, 45)
                    }
                    c.drawRect(x, cy - h / 2f, x + bw, cy + h / 2f, fill)
                }
            }
            "neon" -> {
                val h = dp(4f)
                fill.color = withAlpha(t.textMuted, 35)
                rect.set(left, cy - h / 2f, right, cy + h / 2f)
                c.drawRoundRect(rect, h, h, fill)
                val fr = left + (right - left) * f
                if (fr > left + 1f) {
                    for ((grow, a) in listOf(5f to 30, 2.5f to 70)) {
                        fill.color = withAlpha(t.accent, a)
                        rect.set(left - dp(grow), cy - h / 2f - dp(grow), fr + dp(grow), cy + h / 2f + dp(grow))
                        c.drawRoundRect(rect, h + dp(grow), h + dp(grow), fill)
                    }
                    fill.shader = LinearGradient(left, 0f, right, 0f, t.accent, t.accent2, Shader.TileMode.CLAMP)
                    rect.set(left, cy - h / 2f, fr, cy + h / 2f)
                    c.drawRoundRect(rect, h, h, fill)
                    fill.shader = null
                    fill.color = Color.WHITE
                    c.drawCircle(fr, cy, dp(2.6f), fill)
                }
            }
            "minima" -> {
                val h = dp(2f)
                fill.color = withAlpha(t.textMuted, 40)
                c.drawRect(left, cy - h / 2f, right, cy + h / 2f, fill)
                fill.color = t.accent
                c.drawRect(left, cy - h / 2f, left + (right - left) * f, cy + h / 2f, fill)
            }
            else -> { // clasica
                val h = dp(3.5f)
                fill.color = withAlpha(t.textMuted, 45)
                rect.set(left, cy - h / 2f, right, cy + h / 2f)
                c.drawRoundRect(rect, h, h, fill)
                val fr = left + (right - left) * f
                if (fr > left + 1f) {
                    fill.shader = LinearGradient(left, 0f, right, 0f, t.accent, t.accent2, Shader.TileMode.CLAMP)
                    rect.set(left, cy - h / 2f, fr, cy + h / 2f)
                    c.drawRoundRect(rect, h, h, fill)
                    fill.shader = null
                }
            }
        }
    }

    // ================================================================ trail

    private class TP(
        var x: Float, var y: Float, val vx: Float, val vy: Float,
        val born: Long, val life: Long, val color: Int, val size: Float, val star: Boolean
    )

    private val parts = ArrayList<TP>()
    private var spawnCounter = 0

    /** Called for each new finger point while gliding. */
    fun onTrailPoint(style: String, t: KbTheme, x: Float, y: Float) {
        if (style != "fuego" && style != "chispas") return
        spawnCounter++
        if (spawnCounter % 2 != 0) return
        val now = SystemClock.uptimeMillis()
        if (parts.size > 140) parts.removeAt(0)
        if (style == "fuego") {
            val col = lerpColor(Color.parseColor("#FF3C00"), Color.parseColor("#FFD24A"), Random.nextFloat())
            parts.add(TP(x + (Random.nextFloat() - 0.5f) * dp(6f), y, (Random.nextFloat() - 0.5f) * dp(1.2f),
                -dp(0.8f) - Random.nextFloat() * dp(1.5f), now, 450L + Random.nextLong(250), col, dp(2f + Random.nextFloat() * 3f), false))
        } else {
            val col = if (Random.nextBoolean()) Color.WHITE else t.accent2
            parts.add(TP(x + (Random.nextFloat() - 0.5f) * dp(14f), y + (Random.nextFloat() - 0.5f) * dp(14f),
                (Random.nextFloat() - 0.5f) * dp(0.6f), (Random.nextFloat() - 0.5f) * dp(0.6f),
                now, 500L + Random.nextLong(300), col, dp(3f + Random.nextFloat() * 4f), true))
        }
    }

    fun clearParticles() = parts.clear()

    /** @return true while particles are still animating */
    fun drawParticles(c: Canvas): Boolean {
        if (parts.isEmpty()) return false
        val now = SystemClock.uptimeMillis()
        val it = parts.iterator()
        fill.shader = null
        while (it.hasNext()) {
            val p = it.next()
            val age = (now - p.born).toFloat() / p.life
            if (age >= 1f) { it.remove(); continue }
            p.x += p.vx
            p.y += p.vy
            drawParticle(c, p.x, p.y, p.size * (1f - age * 0.6f), p.color, (255 * (1f - age)).toInt(), p.star, age * 4f)
        }
        return parts.isNotEmpty()
    }

    private fun drawParticle(c: Canvas, x: Float, y: Float, size: Float, color: Int, alpha: Int, star: Boolean, rot: Float) {
        if (star) {
            stroke.shader = null
            stroke.strokeWidth = max(dp(1f), size / 4f)
            stroke.color = withAlpha(color, alpha)
            val dx = cos(rot) * size
            val dy = sin(rot) * size
            c.drawLine(x - dx, y - dy, x + dx, y + dy, stroke)
            c.drawLine(x + dy, y - dx, x - dy, y + dx, stroke)
        } else {
            fill.color = withAlpha(color, alpha)
            c.drawCircle(x, y, size / 2f, fill)
        }
    }

    /**
     * Draws a finger path. [alpha] 0..255 lets the trail fade out after the finger lifts.
     * [preview] adds a few static particles for the settings screen.
     */
    fun drawTrail(c: Canvas, style: String, t: KbTheme, xs: FloatArray, ys: FloatArray, n: Int, alpha: Int, preview: Boolean = false) {
        if (n < 2) return
        stroke.shader = null
        when (style) {
            "cometa" -> {
                val start = max(1, n - 160)
                val span = (n - start).toFloat()
                for (i in start until n) {
                    val f = (i - start + 1) / span
                    stroke.strokeWidth = dp(1.5f + 8f * f)
                    stroke.color = withAlpha(lerpColor(t.accent, t.accent2, f), (alpha * f.pow(0.7f)).toInt())
                    c.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], stroke)
                }
                fill.color = withAlpha(Color.WHITE, alpha)
                c.drawCircle(xs[n - 1], ys[n - 1], dp(4.5f), fill)
            }
            "arcoiris" -> {
                for (i in 1 until n) {
                    stroke.strokeWidth = dp(5.5f)
                    stroke.color = Color.HSVToColor(alpha, floatArrayOf((i * 7f) % 360f, 0.85f, 1f))
                    c.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], stroke)
                }
            }
            "fuego" -> {
                val start = max(1, n - 160)
                val span = (n - start).toFloat()
                val red = Color.parseColor("#FF2A00")
                val yellow = Color.parseColor("#FFE066")
                for (pass in 0..1) for (i in start until n) {
                    val f = (i - start + 1) / span
                    val w = dp(2f + 8f * f)
                    if (pass == 0) {
                        stroke.strokeWidth = w * 1.9f
                        stroke.color = withAlpha(Color.parseColor("#FF6A00"), (alpha * 0.22f * f).toInt())
                    } else {
                        stroke.strokeWidth = w
                        stroke.color = withAlpha(lerpColor(red, yellow, f), (alpha * f.pow(0.5f)).toInt())
                    }
                    c.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], stroke)
                }
                if (preview) {
                    val rnd = Random(3)
                    for (k in 0 until 10) {
                        val i = n / 3 + rnd.nextInt(n - n / 3)
                        drawParticle(c, xs[i] + rnd.nextFloat() * dp(8f) - dp(4f), ys[i] - rnd.nextFloat() * dp(10f),
                            dp(3f), lerpColor(red, yellow, rnd.nextFloat()), 220, false, 0f)
                    }
                }
            }
            "neon" -> {
                path.reset()
                path.moveTo(xs[0], ys[0])
                for (i in 1 until n) path.lineTo(xs[i], ys[i])
                for ((w, a) in listOf(15f to 0.10f, 9f to 0.22f, 5.5f to 0.55f)) {
                    stroke.strokeWidth = dp(w)
                    stroke.color = withAlpha(t.accent, (alpha * a).toInt())
                    c.drawPath(path, stroke)
                }
                stroke.strokeWidth = dp(2f)
                stroke.color = withAlpha(Color.WHITE, alpha)
                c.drawPath(path, stroke)
            }
            "chispas" -> {
                path.reset()
                path.moveTo(xs[0], ys[0])
                for (i in 1 until n) path.lineTo(xs[i], ys[i])
                stroke.strokeWidth = dp(2.2f)
                stroke.color = withAlpha(t.accent2, (alpha * 0.7f).toInt())
                c.drawPath(path, stroke)
                if (preview) {
                    val rnd = Random(7)
                    for (k in 0 until 9) {
                        val i = rnd.nextInt(n)
                        drawParticle(c, xs[i] + rnd.nextFloat() * dp(14f) - dp(7f), ys[i] + rnd.nextFloat() * dp(14f) - dp(7f),
                            dp(3f + rnd.nextFloat() * 3f), if (k % 2 == 0) Color.WHITE else t.accent2, 230, true, rnd.nextFloat() * 3f)
                    }
                }
            }
            else -> { // clasica
                path.reset()
                path.moveTo(xs[0], ys[0])
                for (i in 1 until n) path.lineTo(xs[i], ys[i])
                stroke.strokeWidth = dp(7f)
                stroke.color = withAlpha(t.accent, alpha / 3)
                c.drawPath(path, stroke)
                stroke.strokeWidth = dp(3.5f)
                stroke.color = withAlpha(t.accent2, alpha)
                c.drawPath(path, stroke)
            }
        }
    }
}
