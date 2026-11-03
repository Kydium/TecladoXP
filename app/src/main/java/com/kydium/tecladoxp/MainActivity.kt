package com.kydium.tecladoxp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.time.LocalDate
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var settings: SharedPreferences
    private var scroll: ScrollView? = null

    private val bg = Color.parseColor("#0E0F14")
    private val cardBg = Color.parseColor("#181A22")
    private val cardBg2 = Color.parseColor("#20232D")
    private val textC = Color.parseColor("#F2F3F5")
    private val muted = Color.parseColor("#8D91A0")
    private val accent = Color.parseColor("#8B5CF6")
    private val accent2 = Color.parseColor("#22D3EE")
    private val gold = Color.parseColor("#FACC15")

    private fun dp(v: Float) = (v * resources.displayMetrics.density)
    private fun dpi(v: Float) = dp(v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        XpManager.init(this)
        settings = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val sv = ScrollView(this)
        sv.setBackgroundColor(bg)
        sv.isFillViewport = true
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dpi(16f), dpi(20f), dpi(16f), dpi(40f))
        sv.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll = sv
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) render()
    }

    // ------------------------------------------------------------ ui helpers

    private fun rounded(color: Int, radius: Float = 18f, stroke: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius)
            if (stroke > 0) setStroke(stroke, strokeColor)
        }

    private fun text(s: String, size: Float, color: Int = textC, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cardBg)
        setPadding(dpi(16f), dpi(16f), dpi(16f), dpi(16f))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dpi(14f)
        layoutParams = lp
    }

    private fun sectionTitle(parent: LinearLayout, s: String) {
        parent.addView(text(s, 17f, textC, true).apply { setPadding(0, 0, 0, dpi(10f)) })
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        text(label, 15f, if (primary) Color.WHITE else textC, true).apply {
            gravity = Gravity.CENTER
            setPadding(dpi(14f), dpi(13f), dpi(14f), dpi(13f))
            background = if (primary) GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(accent, Color.parseColor("#6366F1"))
            ).apply { cornerRadius = dp(14f) } else rounded(cardBg2, 14f)
            setOnClickListener { onClick() }
        }

    private fun fmt(n: Long): String = String.format(Locale.US, "%,d", n)

    // ------------------------------------------------------------ render

    private fun render() {
        root.removeAllViews()
        val (lvl, into, need) = XpManager.levelInfo()

        // header
        root.addView(text("Teclado XP", 30f, textC, true))
        root.addView(text("Escribe, gana experiencia y sube de nivel", 14f, muted).apply {
            setPadding(0, dpi(2f), 0, dpi(4f))
        })

        renderSetup()
        renderProfile(lvl, into, need)
        renderStats()
        renderAchievements()
        renderThemes(lvl)
        renderSettings()
        renderTest()
    }

    private fun renderSetup() {
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = current.startsWith("$packageName/")
        if (enabled && selected) return

        val c = card()
        c.background = rounded(cardBg, 18f, dpi(1.5f), accent)
        sectionTitle(c, "Configura tu teclado")
        c.addView(text(
            (if (enabled) "✅" else "1️⃣") + "  Activa Teclado XP en los ajustes\n" +
                (if (selected) "✅" else "2️⃣") + "  Elígelo como teclado actual", 14f, muted
        ).apply { setLineSpacing(dp(4f), 1f); setPadding(0, 0, 0, dpi(12f)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val b1 = button("Activar", !enabled) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        val b2 = button("Elegir teclado", enabled) { imm?.showInputMethodPicker() }
        row.addView(b1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dpi(6f) })
        row.addView(b2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dpi(6f) })
        c.addView(row)
        root.addView(c)
    }

    private fun renderProfile(lvl: Int, into: Long, need: Long) {
        val c = card()
        val frac = if (lvl >= XpManager.MAX_LEVEL) 1f else into.toFloat() / need

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(LevelRing(this, lvl, frac), LinearLayout.LayoutParams(dpi(96f), dpi(96f)))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), 0, 0, 0)
        }
        info.addView(text(XpManager.titleFor(lvl), 22f, textC, true))
        val nextTitleLevel = listOf(3, 6, 10, 15, 20, 30, 40, 50, 75, 100).firstOrNull { it > lvl }
        info.addView(text(
            if (nextTitleLevel != null) "Siguiente rango: ${XpManager.titleFor(nextTitleLevel)} (nivel $nextTitleLevel)"
            else "Rango máximo alcanzado", 12.5f, muted
        ))
        val today = LocalDate.now().toEpochDay()
        val streak = if (XpManager.lastDay >= today - 1) XpManager.streakDays else 0
        val todayXp = if (XpManager.lastDay == today) XpManager.xpToday else 0L
        info.addView(text("🔥 Racha: $streak día" + (if (streak == 1) "" else "s") + "   ⚡ Hoy: ${fmt(todayXp)} XP", 13f, gold).apply {
            setPadding(0, dpi(6f), 0, 0)
        })
        top.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(top)

        c.addView(XpBar(this, frac), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(14f)).apply {
            topMargin = dpi(16f)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(text(
            if (lvl >= XpManager.MAX_LEVEL) "NIVEL MÁXIMO" else "${fmt(into)} / ${fmt(need)} XP", 13f, textC, true
        ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text("Total: ${fmt(XpManager.totalXp)} XP", 13f, muted))
        row.setPadding(0, dpi(6f), 0, 0)
        c.addView(row)

        c.addView(text(
            "Cómo ganar XP: cada palabra da XP según sus letras. Deslizar da +3, usar sugerencias +2 y " +
                "escribir rápido sube tu combo (hasta x2). El primer mensaje del día da un bono por racha.",
            12f, muted
        ).apply { setPadding(0, dpi(12f), 0, 0); setLineSpacing(dp(2f), 1f) })
        root.addView(c)
    }

    private fun renderStats() {
        val c = card()
        sectionTitle(c, "Estadísticas")
        val items = listOf(
            "Palabras" to fmt(XpManager.totalWords),
            "Letras" to fmt(XpManager.totalChars),
            "Deslizando" to fmt(XpManager.glideWords),
            "Autocorrecciones" to fmt(XpManager.corrections),
            "Sugerencias usadas" to fmt(XpManager.suggestionsUsed),
            "Mejor combo" to XpManager.bestCombo.toString()
        )
        for (r in items.indices step 2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in r..minOf(r + 1, items.size - 1)) {
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(cardBg2, 14f)
                    setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f))
                }
                tile.addView(text(items[j].second, 20f, textC, true))
                tile.addView(text(items[j].first, 12f, muted))
                row.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (j == r) rightMargin = dpi(5f) else leftMargin = dpi(5f)
                    bottomMargin = dpi(10f)
                })
            }
            c.addView(row)
        }
        root.addView(c)
    }

    private fun renderAchievements() {
        val c = card()
        val done = XpManager.achievements.count { XpManager.isUnlocked(it.id) }
        sectionTitle(c, "Logros  ($done/${XpManager.achievements.size})")
        for (a in XpManager.achievements) {
            val got = XpManager.isUnlocked(a.id)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpi(7f), 0, dpi(7f))
                alpha = if (got) 1f else 0.55f
            }
            row.addView(text(if (got) "🏆" else "🔒", 20f).apply { setPadding(0, 0, dpi(12f), 0) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(a.name, 15f, if (got) gold else textC, true))
            col.addView(text(a.desc, 12.5f, muted))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(text("+${a.xp} XP", 12.5f, if (got) accent2 else muted, true))
            c.addView(row)
        }
        root.addView(c)
    }

    private fun renderThemes(lvl: Int) {
        val c = card()
        sectionTitle(c, "Temas")
        c.addView(text("Desbloquea temas nuevos subiendo de nivel.", 12.5f, muted).apply { setPadding(0, 0, 0, dpi(10f)) })
        val current = settings.getString("theme", "noche")
        val list = Themes.all
        for (r in list.indices step 3) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0 until 3) {
                val idx = r + j
                val lp = LinearLayout.LayoutParams(0, dpi(104f), 1f).apply {
                    leftMargin = dpi(4f); rightMargin = dpi(4f); bottomMargin = dpi(8f)
                }
                if (idx >= list.size) {
                    row.addView(View(this), lp)
                    continue
                }
                val t = list[idx]
                val unlocked = lvl >= t.unlockLevel
                val sw = ThemeSwatch(this, t, unlocked, t.id == current)
                sw.setOnClickListener {
                    if (unlocked) {
                        settings.edit().putString("theme", t.id).apply()
                        render()
                    } else {
                        android.widget.Toast.makeText(this, "Se desbloquea en el nivel ${t.unlockLevel}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                row.addView(sw, lp)
            }
            c.addView(row)
        }
        root.addView(c)
    }

    private fun switchRow(parent: LinearLayout, label: String, desc: String, key: String, def: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(8f), 0, dpi(8f))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(label, 15f, textC))
        if (desc.isNotEmpty()) col.addView(text(desc, 12f, muted))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        @Suppress("DEPRECATION")
        val sw = Switch(this)
        sw.isChecked = settings.getBoolean(key, def)
        sw.setOnCheckedChangeListener { _, checked -> settings.edit().putBoolean(key, checked).apply() }
        row.addView(sw)
        row.setOnClickListener { sw.isChecked = !sw.isChecked }
        parent.addView(row)
    }

    private fun renderSettings() {
        val c = card()
        sectionTitle(c, "Ajustes")
        switchRow(c, "Escritura deslizante", "Desliza el dedo por las letras para escribir", "glide", true)
        switchRow(c, "Autocorrector", "Corrige al presionar espacio (⌫ para deshacer)", "autocorrect", true)
        switchRow(c, "Vibración al tocar", "", "vibrate", true)
        switchRow(c, "Doble espacio = punto", "", "doublespace", true)
        switchRow(c, "Números en las teclas", "Mantén presionada la fila de arriba", "hints", true)
        switchRow(c, "Mostrar +XP flotante", "", "floatxp", true)

        c.addView(text("Altura del teclado", 15f, textC).apply { setPadding(0, dpi(12f), 0, dpi(8f)) })
        val h = settings.getInt("height", 1)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Baja", "Normal", "Alta").forEachIndexed { i, s ->
            val b = button(s, i == h) {
                settings.edit().putInt("height", i).apply()
                render()
            }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dpi(3f); rightMargin = dpi(3f)
            })
        }
        c.addView(row)

        c.addView(text("Gestos", 15f, textC, true).apply { setPadding(0, dpi(16f), 0, dpi(4f)) })
        c.addView(text(
            "• Mantén presionada una tecla para acentos, números y emojis (en la coma)\n" +
                "• Desliza sobre la barra espaciadora para mover el cursor\n" +
                "• Desliza a la izquierda desde ⌫ para borrar palabras\n" +
                "• Mantén la barra espaciadora para cambiar de teclado\n" +
                "• Toca ⇧ dos veces para bloquear mayúsculas\n" +
                "• Toca tu nivel en el teclado para abrir esta pantalla",
            13f, muted
        ).apply { setLineSpacing(dp(3f), 1f) })

        val reset = button("Reiniciar progreso", false) {
            AlertDialog.Builder(this)
                .setTitle("¿Reiniciar progreso?")
                .setMessage("Perderás tu nivel, XP, logros y estadísticas. Las palabras aprendidas se mantienen.")
                .setPositiveButton("Reiniciar") { _, _ ->
                    XpManager.resetAll()
                    settings.edit().putString("theme", "noche").apply()
                    render()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        c.addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(16f)
        })
        root.addView(c)
    }

    private fun renderTest() {
        val c = card()
        sectionTitle(c, "Pruébalo aquí")
        val et = EditText(this).apply {
            hint = "Escribe algo para ganar XP…"
            setHintTextColor(muted)
            setTextColor(textC)
            textSize = 16f
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            background = rounded(cardBg2, 12f)
            setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(12f))
        }
        c.addView(et)
        root.addView(c)
    }

    // ------------------------------------------------------------ custom views

    private inner class LevelRing(ctx: Context, val lvl: Int, val frac: Float) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val s = minOf(width, height).toFloat()
            val sw = dp(8f)
            r.set(sw, sw, s - sw, s - sw)
            p.style = Paint.Style.FILL
            p.color = cardBg2
            c.drawOval(r, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = sw
            p.strokeCap = Paint.Cap.ROUND
            p.color = Color.parseColor("#2E3140")
            c.drawOval(r, p)
            p.shader = LinearGradient(0f, 0f, s, s, accent, accent2, Shader.TileMode.CLAMP)
            c.drawArc(r, -90f, 360f * frac, false, p)
            p.shader = null
            p.style = Paint.Style.FILL
            p.textAlign = Paint.Align.CENTER
            p.color = muted
            p.textSize = dp(11f)
            c.drawText("NIVEL", s / 2f, s / 2f - dp(14f), p)
            p.color = textC
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = dp(if (lvl >= 100) 26f else 32f)
            c.drawText(lvl.toString(), s / 2f, s / 2f + dp(18f), p)
            p.typeface = Typeface.DEFAULT
        }
    }

    private inner class XpBar(ctx: Context, val frac: Float) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            r.set(0f, 0f, width.toFloat(), h)
            p.shader = null
            p.color = Color.parseColor("#2E3140")
            c.drawRoundRect(r, h / 2, h / 2, p)
            val fw = width * frac
            if (fw > 1f) {
                r.set(0f, 0f, maxOf(fw, h), h)
                p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, accent, accent2, Shader.TileMode.CLAMP)
                c.drawRoundRect(r, h / 2, h / 2, p)
                p.shader = null
            }
        }
    }

    private inner class ThemeSwatch(ctx: Context, val t: KbTheme, val unlocked: Boolean, val selected: Boolean) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            r.set(0f, 0f, w, h)
            p.shader = LinearGradient(0f, 0f, 0f, h, t.bgTop, t.bgBottom, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, dp(14f), dp(14f), p)
            p.shader = null
            // mini keys
            val kw = (w - dp(16f)) / 5f
            val kh = dp(14f)
            for (row in 0 until 3) for (col in 0 until 5) {
                val x = dp(8f) + col * kw
                val y = dp(10f) + row * (kh + dp(4f))
                r.set(x + dp(1.5f), y, x + kw - dp(1.5f), y + kh)
                p.color = if (row == 2 && col == 4) t.accent else if (row == 2 && col == 0) t.keySpecial else t.key
                if (t.rainbow && !(row == 2 && col == 4)) p.color = Color.HSVToColor(floatArrayOf(((row * 5 + col) * 24f) % 360f, 0.55f, 0.6f))
                c.drawRoundRect(r, dp(3f), dp(3f), p)
            }
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = dp(12.5f)
            p.color = t.text
            c.drawText(t.name, w / 2f, h - dp(22f), p)
            p.typeface = Typeface.DEFAULT
            p.textSize = dp(10.5f)
            p.color = t.textMuted
            c.drawText(if (unlocked) (if (selected) "En uso" else "Toca para usar") else "🔒 Nivel ${t.unlockLevel}", w / 2f, h - dp(8f), p)
            if (!unlocked) {
                p.color = Color.argb(120, 0, 0, 0)
                r.set(0f, 0f, w, h)
                c.drawRoundRect(r, dp(14f), dp(14f), p)
            }
            if (selected) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = dp(2.5f)
                p.color = t.accent
                r.set(dp(1.25f), dp(1.25f), w - dp(1.25f), h - dp(1.25f))
                c.drawRoundRect(r, dp(14f), dp(14f), p)
                p.style = Paint.Style.FILL
            }
        }
    }
}
