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
import android.net.Uri
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
import android.widget.Toast
import java.io.File
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

    private val renderer by lazy { StyleRenderer(resources.displayMetrics) }

    // updater state (survives re-renders)
    private var updState = "idle"   // idle, checking, latest, available, downloading, error
    private var updRelease: Updater.Release? = null
    private var updProgress = 0
    private var updStatus: TextView? = null
    private var updButton: TextView? = null
    private var checkedOnce = false

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
        renderUpdate()
        renderProfile(lvl, into, need)
        renderStats()
        renderAchievements()
        renderThemes(lvl)
        renderStylePicker(lvl, "Barra de experiencia", "Cómo se ve tu nivel y tu barra de XP en el teclado.",
            XpBarStyles.all, "xpstyle") { o, unlocked, selected -> BarSwatch(this, o, unlocked, selected) }
        renderStylePicker(lvl, "Estela al deslizar", "El rastro que deja tu dedo al escribir deslizando.",
            TrailStyles.all, "trailstyle") { o, unlocked, selected -> TrailSwatch(this, o, unlocked, selected) }
        renderSettings()
        renderMyWords()
        renderTest()
    }

    private fun styleButton(tv: TextView, primary: Boolean) {
        tv.setTextColor(if (primary) Color.WHITE else textC)
        tv.background = if (primary) GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(accent, Color.parseColor("#6366F1"))
        ).apply { cornerRadius = dp(14f) } else rounded(cardBg2, 14f)
    }

    // ------------------------------------------------------------ updates

    private fun renderUpdate() {
        val c = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text("Actualizaciones", 17f, textC, true))
        val status = text("", 12.5f, muted)
        col.addView(status)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btn = button("Buscar", false) { onUpdateButton() }
        row.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dpi(10f)
        })
        c.addView(row)
        root.addView(c)
        updStatus = status
        updButton = btn
        applyUpdState()
        if (!checkedOnce) {
            checkedOnce = true
            checkUpdates()
        }
    }

    private fun applyUpdState() {
        val st = updStatus ?: return
        val b = updButton ?: return
        val cur = "Versión ${Updater.currentName(this)}"
        val rel = updRelease
        when (updState) {
            "checking" -> { st.text = "$cur · buscando…"; b.text = "…"; styleButton(b, false) }
            "latest" -> { st.text = "$cur · ya tienes la última ✅"; b.text = "Buscar"; styleButton(b, false) }
            "available" -> {
                st.text = "$cur · nueva versión 1.${rel?.code ?: ""} disponible 🎉"
                b.text = "Actualizar"; styleButton(b, true)
            }
            "downloading" -> { st.text = "Descargando 1.${rel?.code ?: ""}… $updProgress%"; b.text = "$updProgress%"; styleButton(b, false) }
            "error" -> { st.text = "$cur · sin conexión con GitHub"; b.text = "Reintentar"; styleButton(b, false) }
            else -> { st.text = cur; b.text = "Buscar"; styleButton(b, false) }
        }
    }

    private fun onUpdateButton() {
        when (updState) {
            "available" -> startDownload()
            "checking", "downloading" -> {}
            else -> checkUpdates()
        }
    }

    private fun checkUpdates() {
        updState = "checking"
        applyUpdState()
        Thread {
            val r = try { Updater.fetchLatest() } catch (_: Exception) { null }
            runOnUiThread {
                updRelease = r
                updState = when {
                    r == null -> "error"
                    r.code > Updater.currentCode(this) -> "available"
                    else -> "latest"
                }
                applyUpdState()
            }
        }.start()
    }

    private fun startDownload() {
        val rel = updRelease ?: return
        if (!Updater.canInstall(this)) {
            Toast.makeText(this, "Permite \"Instalar apps desconocidas\" para Teclado XP y vuelve a tocar Actualizar", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
            }
            return
        }
        updState = "downloading"
        updProgress = 0
        applyUpdState()
        val dest = File(cacheDir, "update.apk")
        Thread {
            val ok = try {
                Updater.download(rel, dest) { p -> runOnUiThread { updProgress = p; applyUpdState() } }
            } catch (_: Exception) { false }
            runOnUiThread {
                if (!ok) {
                    updState = "error"
                    applyUpdState()
                    return@runOnUiThread
                }
                updState = "available"
                applyUpdState()
                try {
                    Updater.install(this, dest)
                } catch (e: Exception) {
                    Toast.makeText(this, "No se pudo instalar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------ style pickers

    private fun renderStylePicker(
        lvl: Int, title: String, desc: String, options: List<StyleOption>, key: String,
        factory: (StyleOption, Boolean, Boolean) -> View
    ) {
        val c = card()
        sectionTitle(c, title)
        c.addView(text(desc, 12.5f, muted).apply { setPadding(0, 0, 0, dpi(10f)) })
        val current = settings.getString(key, "clasica")
        for (r in options.indices step 3) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0 until 3) {
                val idx = r + j
                val lp = LinearLayout.LayoutParams(0, dpi(96f), 1f).apply {
                    leftMargin = dpi(4f); rightMargin = dpi(4f); bottomMargin = dpi(8f)
                }
                if (idx >= options.size) {
                    row.addView(View(this), lp)
                    continue
                }
                val o = options[idx]
                val unlocked = lvl >= o.unlockLevel
                val v = factory(o, unlocked, o.id == current)
                v.setOnClickListener {
                    if (unlocked) {
                        settings.edit().putString(key, o.id).apply()
                        render()
                    } else {
                        Toast.makeText(this, "Se desbloquea en el nivel ${o.unlockLevel}", Toast.LENGTH_SHORT).show()
                    }
                }
                row.addView(v, lp)
            }
            c.addView(row)
        }
        root.addView(c)
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
                        Toast.makeText(this, "Se desbloquea en el nivel ${t.unlockLevel}", Toast.LENGTH_SHORT).show()
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

    private val REQ_ORB = 41
    private val REQ_LEVEL = 42
    private var preview: SoundFx? = null

    /** Row to pick a custom sound file from the phone (or go back to the built-in one). */
    private fun soundRow(parent: LinearLayout, label: String, file: String, req: Int) {
        val f = SoundFx.customFile(this, file)
        val custom = f.exists()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(8f), 0, dpi(8f))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(label, 15f, textC))
        col.addView(text(if (custom) "Personalizado 🎵" else "Sonido incluido", 12f, if (custom) accent2 else muted))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pick = button(if (custom) "Cambiar" else "Elegir", false) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            try { startActivityForResult(i, req) } catch (_: Exception) {
                Toast.makeText(this, "No se pudo abrir el selector de archivos", Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(pick)
        if (custom) {
            val reset = button("✕", false) {
                f.delete()
                Toast.makeText(this, "Volviste al sonido incluido", Toast.LENGTH_SHORT).show()
                render()
            }
            row.addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dpi(6f)
            })
        }
        parent.addView(row)
    }

    @Deprecated("framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val name = when (requestCode) {
            REQ_ORB -> SoundFx.ORB_FILE
            REQ_LEVEL -> SoundFx.LEVEL_FILE
            else -> return
        }
        try {
            val dest = SoundFx.customFile(this, name)
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            if (dest.length() > 3_000_000) {
                dest.delete()
                Toast.makeText(this, "El sonido es muy largo, elige uno corto", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Sonido guardado 🎵", Toast.LENGTH_SHORT).show()
                // short preview
                val fx = preview ?: SoundFx(this).also { preview = it }
                fx.preload()
                window.decorView.postDelayed({
                    if (name == SoundFx.ORB_FILE) fx.playOrb() else fx.playLevelUp()
                }, 400)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo copiar el sonido: ${e.message}", Toast.LENGTH_LONG).show()
        }
        render()
    }

    override fun onDestroy() {
        preview?.release()
        super.onDestroy()
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
        switchRow(c, "Sonidos de XP", "Un \"pling\" al ganar XP y una melodía al subir de nivel", "xpsound", true)
        soundRow(c, "Sonido al ganar XP", SoundFx.ORB_FILE, REQ_ORB)
        soundRow(c, "Sonido al subir de nivel", SoundFx.LEVEL_FILE, REQ_LEVEL)

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

    private var showAllWords = false

    private fun renderMyWords() {
        val dict = Dictionary.get(filesDir)
        dict.ensureUser()
        val words = dict.myWords()
        val c = card()
        sectionTitle(c, "Mis palabras  (${words.size})")
        c.addView(text(
            "El teclado aprende tu forma de escribir: las palabras que usas seguido suben en las sugerencias " +
                "y al deslizar. Si te corrige una palabra tuya, toca ⌫ justo después y la aprende. También puedes agregarlas aquí.",
            12.5f, muted
        ).apply { setPadding(0, 0, 0, dpi(10f)); setLineSpacing(dp(2f), 1f) })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            hint = "Ej: porfa"
            setHintTextColor(muted)
            setTextColor(textC)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = rounded(cardBg2, 12f)
            setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f))
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val add = button("Agregar", true) {
            val w = input.text.toString()
            if (dict.addWord(w)) {
                Toast.makeText(this, "\"${w.trim()}\" agregada ✅", Toast.LENGTH_SHORT).show()
                render()
            } else {
                Toast.makeText(this, "Escribe una sola palabra, solo letras", Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(add, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dpi(8f)
        })
        c.addView(row)

        val shown = if (showAllWords) words else words.take(30)
        var line: LinearLayout? = null
        shown.forEachIndexed { i, w ->
            if (i % 3 == 0) {
                line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dpi(8f), 0, 0)
                }
                c.addView(line)
            }
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(cardBg2, 12f)
                setPadding(dpi(10f), dpi(6f), dpi(6f), dpi(6f))
            }
            chip.addView(text(w, 14f, textC), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            chip.addView(text("✕", 13f, muted).apply {
                setPadding(dpi(8f), 0, dpi(4f), 0)
                setOnClickListener {
                    dict.removeWord(w)
                    render()
                }
            })
            line?.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dpi(3f); rightMargin = dpi(3f)
            })
        }
        val rest = shown.size % 3
        if (rest != 0) repeat(3 - rest) {
            line?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dpi(3f); rightMargin = dpi(3f) })
        }
        if (words.size > 30) {
            c.addView(button(if (showAllWords) "Ver menos" else "Ver todas (${words.size})", false) {
                showAllWords = !showAllWords
                render()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpi(10f)
            })
        }
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

    private fun currentTheme(): KbTheme {
        val t = Themes.byId(settings.getString("theme", "noche"))
        return if (t.unlockLevel <= XpManager.level()) t else Themes.all[0]
    }

    /** Common frame for style previews: theme background, name, lock and selection. */
    private abstract inner class StyleSwatch(ctx: Context, val o: StyleOption, val unlocked: Boolean, val selected: Boolean) : View(ctx) {
        protected val p = Paint(Paint.ANTI_ALIAS_FLAG)
        protected val r = RectF()
        protected val t: KbTheme = currentTheme()

        abstract fun drawContent(c: Canvas, w: Float, h: Float)

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            r.set(0f, 0f, w, h)
            p.shader = LinearGradient(0f, 0f, 0f, h, t.bgTop, t.bgBottom, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, dp(14f), dp(14f), p)
            p.shader = null
            drawContent(c, w, h)
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = dp(12f)
            p.color = t.text
            c.drawText(o.name, w / 2f, h - dp(21f), p)
            p.typeface = Typeface.DEFAULT
            p.textSize = dp(10f)
            p.color = t.textMuted
            c.drawText(if (unlocked) (if (selected) "En uso" else "Toca para usar") else "🔒 Nivel ${o.unlockLevel}", w / 2f, h - dp(8f), p)
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

    private inner class BarSwatch(ctx: Context, o: StyleOption, unlocked: Boolean, selected: Boolean) :
        StyleSwatch(ctx, o, unlocked, selected) {
        override fun drawContent(c: Canvas, w: Float, h: Float) {
            val cy = h * 0.34f
            val lvl = XpManager.level()
            if (o.id == "bloques") {
                renderer.drawBar(c, o.id, t, dp(10f), w - dp(10f), cy + dp(6f), 0.65f, lvl)
                renderer.drawBadge(c, o.id, t, w / 2f, cy - dp(5f), dp(13f), lvl, 0.65f, false)
            } else {
                renderer.drawBadge(c, o.id, t, dp(22f), cy, dp(13f), lvl, 0.65f, false)
                renderer.drawBar(c, o.id, t, dp(40f), w - dp(6f), cy, 0.65f, lvl)
            }
        }
    }

    private inner class TrailSwatch(ctx: Context, o: StyleOption, unlocked: Boolean, selected: Boolean) :
        StyleSwatch(ctx, o, unlocked, selected) {
        override fun drawContent(c: Canvas, w: Float, h: Float) {
            val n = 48
            val xs = FloatArray(n)
            val ys = FloatArray(n)
            for (i in 0 until n) {
                val f = i / (n - 1f)
                xs[i] = dp(12f) + (w - dp(24f)) * f
                ys[i] = h * 0.34f + kotlin.math.sin(f * Math.PI * 2.2).toFloat() * h * 0.15f
            }
            renderer.drawTrail(c, o.id, t, xs, ys, n, 235, preview = true)
        }
    }
}
