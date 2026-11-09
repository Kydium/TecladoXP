package com.kydium.tecladoxp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
        CrashLog.init(this)
        XpManager.init(this)
        settings = getSharedPreferences("settings", Context.MODE_PRIVATE)
        Tuner.load(settings)
        val sv = ScrollView(this)
        sv.setBackgroundColor(bg)
        sv.isFillViewport = true
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dpi(16f), dpi(20f), dpi(16f), dpi(40f))
        sv.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll = sv
        setContentView(sv)
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private var handledLogin: String? = null

    /** Coming back from the login page: tecladoxp://login?rt=…&uid=…&email=… */
    private fun handleLogin(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (data.scheme != "tecladoxp" || data.host != "login") return false
        val rt = data.getQueryParameter("rt") ?: return true
        val uid = data.getQueryParameter("uid") ?: return true
        if (rt == handledLogin) return true      // same intent again (e.g. activity recreated)
        handledLogin = rt
        CloudSync.saveLogin(this, rt, uid, data.getQueryParameter("email") ?: "")
        Toast.makeText(this, "Conectado. Revisando tu cuenta…", Toast.LENGTH_SHORT).show()
        Thread {
            val cloud = try { CloudSync.fetchMeta(this) } catch (_: Exception) { null }
            XpManager.init(this)
            val mine = XpManager.totalXp + XpManager.pendingXp
            runOnUiThread {
                if (cloud != null && cloud.xp > mine) {
                    askBringFromCloud(cloud, mine)
                } else {
                    CloudSync.autoUpload(this, force = true) { ok -> runOnUiThread {
                        Toast.makeText(this, if (ok) "☁️ Tu progreso ya está guardado en tu cuenta" else (CloudSync.lastError(this) ?: "No se pudo guardar"), Toast.LENGTH_LONG).show()
                        render()
                    } }
                    render()
                }
            }
        }.start()
        return true
    }

    private fun askBringFromCloud(cloud: CloudSync.Meta, mine: Long) {
        val date = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale("es")).format(java.util.Date(cloud.time))
        AlertDialog.Builder(this)
            .setTitle("Tienes progreso en tu cuenta")
            .setMessage("En la nube: nivel ${cloud.level} (${cloud.xp} XP), guardado el $date" +
                (if (cloud.device.isNotBlank()) " desde ${cloud.device}" else "") +
                ".\nEn este celular: $mine XP.\n\n¿Traerlo a este celular? Se reemplaza lo de aquí.")
            .setPositiveButton("Traer de la nube") { _, _ -> bringFromCloud() }
            .setNegativeButton("Quedarme con este") { _, _ ->
                CloudSync.autoUpload(this, force = true) { _ -> runOnUiThread { render() } }
            }
            .show()
    }

    private fun bringFromCloud() {
        Toast.makeText(this, "Descargando…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = try { CloudSync.download(this) } catch (e: Exception) { CrashLog.log(e, "nube"); false }
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, "✅ Listo. Abre la app de nuevo", Toast.LENGTH_LONG).show()
                    window.decorView.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 1500)
                } else {
                    Toast.makeText(this, CloudSync.lastError(this) ?: "No hay nada guardado en la nube", Toast.LENGTH_LONG).show()
                    render()
                }
            }
        }.start()
    }

    /** "Exportar chat" → share with Teclado XP. */
    private fun handleShare(intent: Intent?) {
        if (handleLogin(intent)) return
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) learnFromChat(uri)
        intent.action = null
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !training && !tuning) render()
    }

    override fun onPause() {
        super.onPause()
        if (training) stopTraining(false)
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
        renderBackup()
        renderErrorLog()
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
        renderLearnChats()
        renderTrainer()
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
        val nextTitleLevel = listOf(3, 6, 10, 15, 20, 30, 40, 50, 75, 100, 150, 200, 300).firstOrNull { it > lvl }
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
            "${fmt(XpManager.totalXp)} / ${fmt(XpManager.totalFor(lvl + 1))} XP", 13f, textC, true
        ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text("Faltan ${fmt(need - into)}", 13f, muted))
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
        if (requestCode == REQ_CHAT) {
            learnFromChat(uri)
            return
        }
        if (requestCode == REQ_BACKUP_SAVE) {
            try {
                contentResolver.openOutputStream(uri)?.use { Backup.write(this, it) }
                settings.edit().putLong("last_backup", System.currentTimeMillis()).apply()
                Toast.makeText(this, "✅ Copia guardada", Toast.LENGTH_LONG).show()
                render()
            } catch (e: Exception) {
                CrashLog.log(e, "copia")
                Toast.makeText(this, "No se pudo guardar la copia: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode == REQ_BACKUP_AUTO) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {
                // some providers don't keep the permission: we'll still try, and show an error if it fails
            }
            settings.edit().putString("auto_backup_uri", uri.toString()).remove("auto_backup_error").apply()
            Backup.autoIfDue(this, force = true) { ok ->
                runOnUiThread {
                    Toast.makeText(this, if (ok) "✅ Copia automática activada" else "No se pudo escribir en ese archivo", Toast.LENGTH_LONG).show()
                    render()
                }
            }
            return
        }
        if (requestCode == REQ_BACKUP_LOAD) {
            AlertDialog.Builder(this)
                .setTitle("¿Restaurar esta copia?")
                .setMessage("Se reemplaza tu nivel, XP, ajustes y todo lo aprendido por lo que tiene la copia. La app se cerrará para cargarla; vuelve a abrirla.")
                .setPositiveButton("Restaurar") { _, _ ->
                    val ok = try {
                        contentResolver.openInputStream(uri)?.use { Backup.restore(this, it) } ?: false
                    } catch (e: Exception) {
                        CrashLog.log(e, "restaurar"); false
                    }
                    if (!ok) {
                        Toast.makeText(this, "Ese archivo no es una copia de Teclado XP", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "✅ Copia restaurada. Abre la app de nuevo", Toast.LENGTH_LONG).show()
                        window.decorView.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 1500)
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
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

    /** A label plus a row of buttons to pick one value (saved as an Int setting). */
    private fun optionRow(parent: LinearLayout, label: String, desc: String, key: String, def: Int, options: List<Pair<Int, String>>) {
        parent.addView(text(label, 15f, textC).apply { setPadding(0, dpi(12f), 0, if (desc.isEmpty()) dpi(8f) else 0) })
        if (desc.isNotEmpty()) parent.addView(text(desc, 12f, muted).apply { setPadding(0, 0, 0, dpi(8f)) })
        val current = settings.getInt(key, def)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((value, name) in options) {
            val b = button(name, value == current) {
                settings.edit().putInt(key, value).apply()
                render()
            }
            if (options.size > 3) {
                // many options: smaller text so they fit in one line
                b.textSize = 12.5f
                b.setPadding(dpi(2f), dpi(12f), dpi(2f), dpi(12f))
            }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dpi(if (options.size > 3) 2f else 3f); rightMargin = dpi(if (options.size > 3) 2f else 3f)
            })
        }
        parent.addView(row)
    }

    private fun renderSettings() {
        // ---- writing
        val w = card()
        sectionTitle(w, "✍️ Escritura")
        w.addView(text("Idioma del teclado", 15f, textC).apply { setPadding(0, 0, 0, dpi(8f)) })
        val lang = settings.getString("lang", "es")
        val langRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("es" to "Español", "en" to "English", "es_en" to "Ambos").forEach { (id, label) ->
            langRow.addView(button(label, id == lang) {
                settings.edit().putString("lang", id).apply()
                render()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dpi(3f); rightMargin = dpi(3f)
            })
        }
        w.addView(langRow)
        w.addView(text("\"Ambos\" sirve si mezclas español e inglés: entiende los dos, pero corrige un poco menos. " +
            "También puedes cambiarlo manteniendo la barra espaciadora.", 12f, muted)
            .apply { setPadding(0, dpi(6f), 0, dpi(10f)) })
        switchRow(w, "Escritura deslizante", "Desliza el dedo por las letras para escribir", "glide", true)
        switchRow(w, "Autocorrector", "Corrige al presionar espacio (⌫ justo después para deshacer)", "autocorrect", true)
        switchRow(w, "Predecir la siguiente palabra", "Sugiere palabras antes de que empieces a escribir", "nextword", true)
        switchRow(w, "Mayúscula automática", "Al empezar a escribir y después de un punto", "autocaps", true)
        switchRow(w, "Sugerir emojis", "Ej: \"jaja\" → 😂 (tócalo y reemplaza la palabra)", "emojisug", true)
        switchRow(w, "Doble espacio = punto", "", "doublespace", true)
        root.addView(w)

        // ---- keyboard look & feel
        val k = card()
        sectionTitle(k, "⌨️ Teclado")
        optionRow(k, "Altura del teclado", "", "height", 1,
            listOf(4 to "Mínima", 0 to "Baja", 1 to "Normal", 2 to "Alta", 3 to "Máxima"))
        optionRow(k, "Separación de las teclas", "Solo cambia cómo se ven: cada tecla se toca igual que antes", "keyspacing", 1,
            listOf(0 to "Juntas", 1 to "Como Gboard", 2 to "Más separadas"))
        switchRow(k, "Fila de números", "Una fila del 1 al 0 arriba de las letras", "numrow", false)
        optionRow(k, "Modo una mano", "El teclado se achica hacia un lado para escribir con el pulgar. " +
            "También se activa desde ▦ en el teclado.", "onehand", 0,
            listOf(0 to "No", 1 to "Izquierda", 2 to "Derecha"))
        switchRow(k, "Mostrar la tecla al presionar", "Una burbuja con la letra arriba de tu dedo", "preview", true)
        switchRow(k, "Números en las teclas", "Pequeños números en la fila de arriba (mantén para escribirlos)", "hints", true)
        optionRow(k, "Mantener presionado", "Cuánto hay que mantener una tecla para ver acentos y símbolos", "longpress", 320,
            listOf(220 to "Rápido", 320 to "Normal", 450 to "Lento", 600 to "Muy lento"))
        root.addView(k)

        // ---- sound & vibration
        val sv = card()
        sectionTitle(sv, "🔊 Sonido y vibración")
        switchRow(sv, "Vibración al tocar", "", "vibrate", true)
        optionRow(sv, "Fuerza de la vibración", "", "vibstrength", 1,
            listOf(0 to "Suave", 1 to "Media", 2 to "Fuerte"))
        switchRow(sv, "Sonido al tocar las teclas", "El clic del celular (no suena en silencio ni en vibración)", "keysound", false)
        switchRow(sv, "Sonidos de XP", "Un \"pling\" al ganar XP y una melodía al subir de nivel", "xpsound", true)
        soundRow(sv, "Sonido al ganar XP", SoundFx.ORB_FILE, REQ_ORB)
        soundRow(sv, "Sonido al subir de nivel", SoundFx.LEVEL_FILE, REQ_LEVEL)
        root.addView(sv)

        // ---- xp & dictation
        val x = card()
        sectionTitle(x, "⭐ Experiencia y dictado")
        switchRow(x, "Mostrar +XP flotante", "", "floatxp", true)
        optionRow(x, "Sumar la XP después de", "Tiempo sin escribir para que la XP se sume a la barra", "xppause", 1000,
            listOf(500 to "0.5 s", 1000 to "1 s", 2000 to "2 s", 3000 to "3 s"))
        optionRow(x, "Dictado: esperar en silencio", "Cuánto espera el micrófono sin que hables antes de terminar", "voicesilence", 5000,
            listOf(2000 to "2 s", 3000 to "3 s", 5000 to "5 s", 8000 to "8 s"))
        root.addView(x)

        // ---- gestures help
        val g = card()
        sectionTitle(g, "👆 Gestos y atajos")
        g.addView(text(
            "• ▦ (a la izquierda de las sugerencias): portapapeles, edición de texto, una mano y ajustes\n" +
                "• Al copiar algo aparece arriba para pegarlo con un toque\n" +
                "• Mantén presionada una tecla para acentos, números y emojis (en la coma)\n" +
                "• Desliza sobre la barra espaciadora para mover el cursor\n" +
                "• Desliza a la izquierda desde ⌫ para borrar palabras enteras\n" +
                "• Mantén la barra espaciadora para cambiar de idioma\n" +
                "• Toca ⇧ dos veces para bloquear mayúsculas\n" +
                "• ⌫ justo después de una corrección la deshace y aprende tu palabra",
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
        g.addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(16f)
        })
        root.addView(g)
    }

    /** Shown only if the keyboard wrote down an error: lets you copy it to send it. */
    private fun renderErrorLog() {
        val log = CrashLog.read(this) ?: return
        val c = card()
        sectionTitle(c, "⚠️ Registro de errores")
        c.addView(text(
            "El teclado anotó un problema (y lo evitó para no cerrarse). Si notaste algo raro, " +
                "copia esto y envíalo para poder arreglarlo.", 12.5f, muted
        ).apply { setPadding(0, 0, 0, dpi(8f)); setLineSpacing(dp(2f), 1f) })
        val preview = log.lines().take(7).joinToString("\n")
        c.addView(text(preview, 11f, textC).apply {
            typeface = Typeface.MONOSPACE
            background = rounded(cardBg2, 10f)
            setPadding(dpi(10f), dpi(8f), dpi(10f), dpi(8f))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpi(10f), 0, 0)
        }
        row.addView(button("Copiar", true) {
            try {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Error Teclado XP", log))
                Toast.makeText(this, "Copiado. Pégalo en el chat para enviarlo", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dpi(4f) })
        row.addView(button("Borrar", false) {
            CrashLog.clear(this)
            render()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dpi(4f) })
        c.addView(row)
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

        TouchModel.init(this)
        val touchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(14f), 0, dpi(2f))
        }
        val tk = TouchModel.learnedKeys
        touchRow.addView(text(
            (if (tk == 0) "👆 Aún aprendiendo dónde tocas cada tecla"
            else "👆 Teclas ajustadas a tu forma de tocar: $tk") +
                "\n〰️ Formas de deslizar aprendidas: ${dict.learnedShapes}",
            13f, textC
        ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (tk > 0) touchRow.addView(button("Reiniciar", false) {
            TouchModel.reset()
            render()
        })
        c.addView(touchRow)

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

        // words removed with a long-press on a suggestion
        val blocked = dict.blockedWords()
        c.addView(text(
            if (blocked.isEmpty()) "🚫 Mantén presionada una sugerencia para que no vuelva a aparecer."
            else "🚫 Quitadas de las sugerencias (${blocked.size}) · toca ↺ para recuperarla",
            13f, if (blocked.isEmpty()) muted else textC
        ).apply { setPadding(0, dpi(16f), 0, dpi(2f)) })
        var bline: LinearLayout? = null
        blocked.forEachIndexed { i, w ->
            if (i % 3 == 0) {
                bline = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dpi(8f), 0, 0)
                }
                c.addView(bline)
            }
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(cardBg2, 12f)
                setPadding(dpi(10f), dpi(6f), dpi(6f), dpi(6f))
            }
            chip.addView(text(w, 14f, muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            chip.addView(text("↺", 15f, accent2, true).apply {
                setPadding(dpi(8f), 0, dpi(4f), 0)
                setOnClickListener {
                    dict.unblockWord(w)
                    Toast.makeText(this@MainActivity, "\"$w\" vuelve a las sugerencias", Toast.LENGTH_SHORT).show()
                    render()
                }
            })
            bline?.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dpi(3f); rightMargin = dpi(3f)
            })
        }
        val brest = blocked.size % 3
        if (brest != 0) repeat(3 - brest) {
            bline?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dpi(3f); rightMargin = dpi(3f) })
        }
        root.addView(c)
    }

    // ------------------------------------------------------------ learn from chats

    private val REQ_CHAT = 43

    private fun renderLearnChats() {
        val c = card()
        sectionTitle(c, "Aprender de mis chats")
        c.addView(text(
            "Para que el teclado adivine como tú escribes, dale tus propios mensajes. En WhatsApp: abre un chat → ⋮ → " +
                "Más → Exportar chat → Sin archivos, y guárdalo o compártelo con Teclado XP. Luego elige el archivo aquí " +
                "y dime cuál eres tú: solo aprende de tus mensajes. Todo se queda en tu celular.",
            12.5f, muted
        ).apply { setPadding(0, 0, 0, dpi(10f)); setLineSpacing(dp(2f), 1f) })
        c.addView(button("Elegir chat exportado (.txt o .zip)", true) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            try { startActivityForResult(i, REQ_CHAT) } catch (_: Exception) {
                Toast.makeText(this, "No se pudo abrir el selector de archivos", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(c)
    }

    /** Reads a WhatsApp export (.txt, or .zip containing it) and returns its text. */
    private fun readChat(uri: Uri): String? {
        return try {
            val head = contentResolver.openInputStream(uri)?.use { s ->
                val b = ByteArray(4); val n = s.read(b); if (n == 4) b else null
            }
            val isZip = head != null && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            contentResolver.openInputStream(uri)?.use { input ->
                if (isZip) {
                    val zip = java.util.zip.ZipInputStream(input)
                    var e = zip.nextEntry
                    var text: String? = null
                    while (e != null) {
                        if (e.name.endsWith(".txt", true)) { text = zip.readBytes().toString(Charsets.UTF_8); break }
                        e = zip.nextEntry
                    }
                    text
                } else input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Splits a WhatsApp export into (author, message) pairs. */
    private fun parseChat(text: String): List<Pair<String, String>> {
        val line = Regex("""^\u200E?\[?\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4},?[\s  ]+\d{1,2}:\d{2}(?::\d{2})?(?:[\s  ]?[aApP]\.?[\s  ]?[mM]\.?)?\]?[\s  ]*[-–]?[\s  ]*([^:]{1,60}):[\s  ](.*)$""")
        val out = ArrayList<Pair<String, String>>()
        for (raw in text.split('\n')) {
            val l = raw.trimEnd('\r')
            val m = line.find(l)
            if (m != null) out.add(Pair(m.groupValues[1].trim(), m.groupValues[2]))
            else if (out.isNotEmpty() && l.isNotBlank()) {
                val last = out.removeAt(out.size - 1)
                out.add(Pair(last.first, last.second + "\n" + l))
            }
        }
        val skip = listOf("<multimedia omitido>", "<media omitted>", "se eliminó este mensaje", "this message was deleted",
            "mensaje eliminado", "null", "<se editó este mensaje.>")
        return out.filter { (_, msg) -> skip.none { msg.trim().lowercase().contains(it) } && !msg.contains("http") }
    }

    private fun learnFromChat(uri: Uri) {
        Toast.makeText(this, "Leyendo el chat…", Toast.LENGTH_SHORT).show()
        Thread {
            val text = readChat(uri)
            val msgs = if (text != null) parseChat(text) else emptyList()
            runOnUiThread {
                if (msgs.isEmpty()) {
                    Toast.makeText(this, "No encontré mensajes. Usa \"Exportar chat\" de WhatsApp (.txt)", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val authors = msgs.groupingBy { it.first }.eachCount().entries.sortedByDescending { it.value }.take(12)
                val names = authors.map { "${it.key}  (${it.value} mensajes)" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("¿Cuál eres tú?")
                    .setItems(names) { _, which ->
                        val me = authors[which].key
                        val mine = msgs.filter { it.first == me }.joinToString("\n") { it.second }
                        Thread {
                            val dict = Dictionary.get(filesDir)
                            val n = dict.learnText(mine)
                            dict.saveAsync()
                            runOnUiThread {
                                Toast.makeText(this, "Listo ✅ Aprendí de ${authors[which].value} mensajes ($n palabras)", Toast.LENGTH_LONG).show()
                                render()
                            }
                        }.start()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }.start()
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

    // ------------------------------------------------------------ swipe trainer

    private var training = false
    private var trainQueue = ArrayList<String>()
    private var trainWord = ""
    private var trainTries = 0
    private var trainHits = 0
    private var trainWordView: TextView? = null
    private var trainStatus: TextView? = null
    private var trainScore: TextView? = null
    private var trainField: EditText? = null

    private val trainWords = ("hola que como estas bien gracias porque pero también siempre nada ahora " +
        "mañana después entonces cuando donde quiero tengo puedo vamos hacer decir sabes mucho poco " +
        "casa trabajo amigo hermano mamá papá comer dormir hablar escribir mensaje teléfono celular " +
        "noche tarde temprano semana ayer hoy todo todos alguien nadie cosa tiempo verdad claro listo " +
        "bueno mejor peor nunca otra vez llegar salir volver esperar mira oye pues igual creo parece").split(" ")

    private fun renderTrainer() {
        val c = card()
        sectionTitle(c, "🎯 Entrenador de deslizado")
        if (!training) {
            c.addView(text(
                "Te muestro una palabra, la escribes deslizando y el teclado aprende tu forma de hacerla. " +
                    "También guarda tus deslizadas de todos los días (la palabra que querías y lo que adivinó). " +
                    "Cuando tengas bastantes, copia el código y envíamelo para mejorar la predicción.",
                12.5f, muted
            ).apply { setPadding(0, 0, 0, dpi(10f)); setLineSpacing(dp(2f), 1f) })
            val n = GlideLog.count
            val miss = GlideLog.misses
            c.addView(text(
                if (n == 0) "Aún no hay deslizadas guardadas"
                else "〰️ Deslizadas guardadas: $n  ·  acertó ${n - miss} (${(100 * (n - miss)) / n}%)",
                13f, textC
            ).apply { setPadding(0, 0, 0, dpi(10f)) })
            c.addView(button("Empezar a entrenar", true) { startTraining() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // improve the recognizer itself with the saved swipes
            val tuneInfo = text(
                when {
                    tuning -> "⚙️ Probando ajustes con tus deslizadas…"
                    n < Tuner.MIN_SAMPLES -> "⚙️ Con ${Tuner.MIN_SAMPLES} deslizadas guardadas podrás mejorar el reconocimiento con tus propios trazos (llevas $n)."
                    else -> "⚙️ Ajusta el reconocedor a tu forma de deslizar usando tus $n deslizadas." +
                        (settings.getString("tune_result", null)?.let { "\nÚltimo ajuste: $it" } ?: "")
                }, 12.5f, muted
            ).apply { setPadding(0, dpi(14f), 0, dpi(6f)); setLineSpacing(dp(2f), 1f) }
            tuneStatus = tuneInfo
            c.addView(tuneInfo)
            if (n >= Tuner.MIN_SAMPLES && !tuning) {
                val trow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                trow.addView(button("Mejorar con mis datos", true) { startTuning() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dpi(3f) })
                if (Tuner.isTuned(settings)) trow.addView(button("Restablecer", false) {
                    Tuner.reset(settings)
                    Toast.makeText(this, "Volviste a los ajustes de fábrica", Toast.LENGTH_SHORT).show()
                    render()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dpi(3f) })
                c.addView(trow)
            }
            if (n > 0) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dpi(8f), 0, 0)
                }
                row.addView(button("Copiar código", false) { exportLog(false) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dpi(3f) })
                row.addView(button("Compartir", false) { exportLog(true) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dpi(3f); rightMargin = dpi(3f) })
                row.addView(button("Borrar", false) {
                    AlertDialog.Builder(this)
                        .setTitle("¿Borrar las deslizadas guardadas?")
                        .setMessage("Lo que el teclado ya aprendió se mantiene.")
                        .setPositiveButton("Borrar") { _, _ -> GlideLog.clear(); render() }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dpi(3f) })
                c.addView(row)
            }
            root.addView(c)
            return
        }
        c.addView(text("Desliza esta palabra:", 13f, muted))
        val wv = text(trainWord, 34f, textC, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dpi(8f), 0, dpi(4f))
        }
        trainWordView = wv
        c.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val st = text(" ", 15f, muted).apply { gravity = Gravity.CENTER }
        trainStatus = st
        c.addView(st, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val et = EditText(this).apply {
            hint = "Desliza aquí con Teclado XP…"
            setHintTextColor(muted)
            setTextColor(textC)
            textSize = 18f
            inputType = InputType.TYPE_CLASS_TEXT
            background = rounded(cardBg2, 12f)
            setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(12f))
        }
        trainField = et
        c.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(10f)
        })
        val sc = text("", 13f, muted).apply { setPadding(0, dpi(8f), 0, 0) }
        trainScore = sc
        c.addView(sc)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpi(10f), 0, 0)
        }
        row.addView(button("Saltar", false) { nextTrainWord() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dpi(4f) })
        row.addView(button("Terminar", true) { stopTraining(true) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dpi(4f) })
        c.addView(row)
        updateTrainScore()
        root.addView(c)
    }

    private val REQ_BACKUP_SAVE = 44
    private val REQ_BACKUP_LOAD = 45
    private val REQ_BACKUP_AUTO = 46

    /** Save / restore everything (level, XP, settings, learned words…) as a file, e.g. in Google Drive. */
    private fun renderBackup() {
        val c = card()
        sectionTitle(c, "☁️ Tu cuenta")
        if (!CloudSync.signedIn(this)) {
            c.addView(text(
                "Inicia sesión con Google (la misma cuenta de My Wallet) y tu nivel, XP, logros, ajustes y palabras " +
                    "aprendidas se guardan solos en tu cuenta. Si cambias de celular, solo vuelves a iniciar sesión.",
                12.5f, muted
            ).apply { setPadding(0, 0, 0, dpi(10f)); setLineSpacing(dp(2f), 1f) })
            c.addView(button("Iniciar sesión con Google", true) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CloudSync.LOGIN_PAGE)))
                } catch (_: Exception) {
                    Toast.makeText(this, "No se pudo abrir el navegador", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            val up = CloudSync.lastUpload(this)
            val err = CloudSync.lastError(this)
            c.addView(text(
                "Conectado: ${CloudSync.email(this)}\n" +
                    (if (up == 0L) "Aún no se guardó nada." else "Guardado en la nube: " +
                        java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("es")).format(java.util.Date(up))) +
                    "\nSe guarda solo al cerrar el teclado (cada 20 min como máximo)." +
                    (if (err != null) "\n⚠️ $err" else ""),
                12.5f, if (err != null) textC else muted
            ).apply { setPadding(0, 0, 0, dpi(10f)); setLineSpacing(dp(2f), 1f) })
            val crow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            crow.addView(button("Guardar ahora", true) {
                CloudSync.autoUpload(this, force = true) { ok -> runOnUiThread {
                    Toast.makeText(this, if (ok) "☁️ Guardado" else (CloudSync.lastError(this) ?: "No se pudo guardar"), Toast.LENGTH_LONG).show()
                    render()
                } }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dpi(3f) })
            crow.addView(button("Traer de la nube", false) {
                Thread {
                    val cloud = try { CloudSync.fetchMeta(this) } catch (_: Exception) { null }
                    XpManager.init(this)
                    val mine = XpManager.totalXp + XpManager.pendingXp
                    runOnUiThread {
                        if (cloud == null) Toast.makeText(this, CloudSync.lastError(this) ?: "No hay nada guardado en la nube", Toast.LENGTH_LONG).show()
                        else askBringFromCloud(cloud, mine)
                    }
                }.start()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dpi(3f) })
            c.addView(crow)
            c.addView(button("Cerrar sesión", false) {
                CloudSync.signOut(this)
                render()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6f) })
        }
        root.addView(c)
        renderFileBackup()
    }

    private fun renderFileBackup() {
        val c = card()
        sectionTitle(c, "💾 Copia en archivo")
        val last = settings.getLong("last_backup", 0L)
        val when_ = if (last == 0L) "Aún no has guardado ninguna copia."
        else "Última copia: " + java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale("es")).format(java.util.Date(last)) +
            (if (System.currentTimeMillis() - last > 7L * 86_400_000) "  ⚠️ hace más de una semana" else "")
        c.addView(text(
            "Guarda tu nivel, XP, logros, ajustes, palabras aprendidas y tu forma de deslizar en un archivo. " +
                "Elige Google Drive al guardar y quedará en tu cuenta: si cambias de celular o reinstalas, " +
                "lo recuperas con Restaurar.\n$when_", 12.5f, muted
        ).apply { setPadding(0, 0, 0, dpi(10f)); setLineSpacing(dp(2f), 1f) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Guardar copia", true) {
            try {
                val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    setType("application/zip")
                    putExtra(Intent.EXTRA_TITLE, "TecladoXP-copia-$day.zip")
                }, REQ_BACKUP_SAVE)
            } catch (_: Exception) {
                Toast.makeText(this, "No se pudo abrir el selector de archivos", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dpi(4f) })
        row.addView(button("Restaurar", false) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    setType("*/*")
                }, REQ_BACKUP_LOAD)
            } catch (_: Exception) {
                Toast.makeText(this, "No se pudo abrir el selector de archivos", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dpi(4f) })
        c.addView(row)

        // automatic copy
        val auto = Backup.autoUri(this)
        val err = settings.getString("auto_backup_error", null)
        c.addView(text(
            when {
                auto == null -> "🔄 Copia automática: desactivada. Actívala, elige dónde guardarla (Drive) una sola vez " +
                    "y después se actualiza sola cada vez que cierras el teclado (como máximo cada 6 horas)."
                err != null -> "⚠️ La copia automática falló la última vez: $err\nVuelve a activarla eligiendo el archivo otra vez."
                else -> "🔄 Copia automática: activada. Se actualiza sola en el mismo archivo cuando cierras el teclado."
            }, 12.5f, if (err != null) textC else muted
        ).apply { setPadding(0, dpi(14f), 0, dpi(8f)); setLineSpacing(dp(2f), 1f) })
        val arow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        arow.addView(button(if (auto == null || err != null) "Activar copia automática" else "Guardar ahora", auto == null || err != null) {
            if (auto != null && err == null) {
                Backup.autoIfDue(this, force = true) { ok ->
                    runOnUiThread {
                        Toast.makeText(this, if (ok) "✅ Copia actualizada" else "No se pudo actualizar la copia", Toast.LENGTH_SHORT).show()
                        render()
                    }
                }
            } else try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    setType("application/zip")
                    putExtra(Intent.EXTRA_TITLE, "TecladoXP-copia-automatica.zip")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }, REQ_BACKUP_AUTO)
            } catch (_: Exception) {
                Toast.makeText(this, "No se pudo abrir el selector de archivos", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dpi(4f) })
        if (auto != null) arow.addView(button("Desactivar", false) {
            try {
                contentResolver.releasePersistableUriPermission(android.net.Uri.parse(auto),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {
            }
            settings.edit().remove("auto_backup_uri").remove("auto_backup_error").apply()
            render()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dpi(4f) })
        c.addView(arow)
        root.addView(c)
    }

    private var tuning = false
    private var tuneStatus: TextView? = null

    private fun startTuning() {
        if (tuning || training) return
        tuning = true
        render()
        val dict = Dictionary.get(filesDir)
        Thread {
            if (!dict.loaded) try {
                // the keyboard isn't running in this moment: load the dictionary here
                val lang = settings.getString("lang", "es") ?: "es"
                val src = ArrayList<Pair<java.io.InputStream, Int>>()
                val ng = ArrayList<java.io.InputStream>()
                if (lang != "en") { src.add(Pair(assets.open("es.txt"), 0)); ng.add(assets.open("ngram_es.txt")) }
                if (lang != "es") { src.add(Pair(assets.open("en.txt"), if (lang == "en") 0 else -30)); ng.add(assets.open("ngram_en.txt")) }
                dict.load(src, ng, lang)
                src.forEach { it.first.close() }; ng.forEach { it.close() }
            } catch (e: Exception) { CrashLog.log(e, "ajuste") }
            val r = Tuner.run(dict, settings) { done, total ->
                runOnUiThread { tuneStatus?.text = "⚙️ Probando ajustes con tus deslizadas… ${100 * done / total}%" }
            }
            runOnUiThread {
                tuning = false
                val msg = when {
                    r == null -> "No se pudo ajustar (faltan datos)"
                    r.after > r.before -> "✅ Acierta más: ${100 * r.before / r.total}% → ${100 * r.after / r.total}% de tus deslizadas"
                    else -> "Ya estaba en su mejor punto para tus trazos (${100 * r.before / r.total}%)"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                render()
            }
        }.start()
    }

    private fun startTraining() {
        if (tuning) return
        val dict = Dictionary.get(filesDir)
        dict.ensureUser()
        val pool = LinkedHashSet<String>()
        pool.addAll(GlideLog.missedWords(15))          // the ones it got wrong first
        val rest = (trainWords + dict.myWords().take(20)).filter { it.length > 1 }.shuffled()
        pool.addAll(rest)
        trainQueue = ArrayList(pool)
        trainTries = 0
        trainHits = 0
        training = true
        trainWord = trainQueue.removeAt(0)
        Trainer.target = trainWord
        Trainer.onResult = { guesses -> onTrainResult(guesses) }
        render()
        val et = trainField ?: return
        et.requestFocus()
        et.postDelayed({
            try { getSystemService(InputMethodManager::class.java)?.showSoftInput(et, 0) } catch (_: Exception) {}
        }, 200)
        scroll?.postDelayed({ scroll?.smoothScrollTo(0, (et.parent as? View)?.top ?: 0) }, 350)
    }

    private fun onTrainResult(guesses: List<String>) {
        if (!training) return
        val got = guesses.firstOrNull()?.lowercase() ?: ""
        trainTries++
        val ok = got == trainWord.lowercase()
        if (ok) trainHits++
        trainStatus?.text = if (ok) "✅ ¡Bien!" else if (got.isEmpty()) "❌ No reconoció nada" else "❌ Salió «$got» · ya lo aprendí"
        trainStatus?.setTextColor(if (ok) Color.parseColor("#4ADE80") else Color.parseColor("#F87171"))
        updateTrainScore()
        trainWordView?.postDelayed({ if (training) nextTrainWord() }, if (ok) 550L else 1300L)
    }

    private fun nextTrainWord() {
        if (!training) return
        if (trainQueue.isEmpty()) trainQueue = ArrayList(trainWords.shuffled())
        trainWord = trainQueue.removeAt(0)
        Trainer.target = trainWord
        trainWordView?.text = trainWord
        trainStatus?.text = " "
        trainField?.setText("")
    }

    private fun updateTrainScore() {
        trainScore?.text = if (trainTries == 0) "Desliza sin miedo: los errores también le enseñan"
        else "Aciertos: $trainHits de $trainTries  (${100 * trainHits / trainTries}%)"
    }

    private fun stopTraining(rerender: Boolean) {
        training = false
        Trainer.target = null
        Trainer.onResult = null
        Dictionary.get(filesDir).saveAsync()
        trainField = null; trainWordView = null; trainStatus = null; trainScore = null
        if (rerender) render()
    }

    private fun exportLog(share: Boolean) {
        val code = try {
            GlideLog.export(Updater.currentName(this), settings.getString("lang", "es") ?: "es")
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo armar el código: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        if (share) {
            try {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code)
                startActivity(Intent.createChooser(i, "Enviar código de deslizado"))
            } catch (_: Exception) {
                Toast.makeText(this, "No se pudo compartir", Toast.LENGTH_SHORT).show()
            }
        } else {
            try {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Deslizado Teclado XP", code))
                Toast.makeText(this, "Código copiado (${code.length / 1000} mil letras). Pégalo en el chat", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
            }
        }
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
