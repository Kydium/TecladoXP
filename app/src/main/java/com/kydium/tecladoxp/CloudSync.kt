package com.kydium.tecladoxp

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Keeps the essential progress in your Google account, using the Firebase of My Wallet
 * (Realtime Database, folder teclado/{uid}). You sign in once on the My Wallet site and the
 * session comes back to the app; from then on it uploads by itself when you close the keyboard.
 *
 * Saved: level, XP, stats and achievements, settings, touch model, learned and removed words.
 * Not saved (too big / only useful on this phone): swipe log, learned swipe shapes, sounds.
 */
object CloudSync {
    private const val API_KEY = "AIzaSyCRKaG35O-EjV8vtX-xXJ52zZKr56hRZsk"
    private const val DB = "https://bingo-a26fb-default-rtdb.firebaseio.com"
    const val LOGIN_PAGE = "https://kydium.github.io/TecladoXP/login.html"
    private const val EVERY = 20L * 60_000          // upload at most every 20 minutes
    private val PREFS = listOf("xp", "settings", "touch")
    private val FILES = listOf("user_words.txt", "blocked_words.txt")
    private val LOCAL_ONLY = setOf("last_backup", "chip_used", "chip_used_h", "auto_backup_uri", "auto_backup_error")

    @Volatile private var busy = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    fun email(ctx: Context): String? = prefs(ctx).getString("email", null)
    fun signedIn(ctx: Context) = prefs(ctx).getString("rt", null) != null
    fun lastUpload(ctx: Context) = prefs(ctx).getLong("last_up", 0L)
    fun lastError(ctx: Context): String? = prefs(ctx).getString("error", null)

    fun saveLogin(ctx: Context, refreshToken: String, uid: String, email: String) {
        prefs(ctx).edit().putString("rt", refreshToken).putString("uid", uid).putString("email", email)
            .remove("id").remove("id_exp").remove("error").putLong("last_up", 0L).apply()
    }

    fun signOut(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    // ---------------------------------------------------------------- http

    private fun http(method: String, url: String, body: String?, form: Boolean = false): Pair<Int, String> {
        val con = URL(url).openConnection() as HttpURLConnection
        con.requestMethod = method
        con.connectTimeout = 15_000
        con.readTimeout = 20_000
        if (body != null) {
            con.doOutput = true
            con.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
            con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = con.responseCode
        val text = try {
            (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (_: Exception) {
            ""
        }
        con.disconnect()
        return Pair(code, text)
    }

    /** A valid ID token (refreshed with the saved session when it expires). */
    private fun idToken(ctx: Context): Pair<String, String>? {
        val p = prefs(ctx)
        val uid = p.getString("uid", null) ?: return null
        val cached = p.getString("id", null)
        if (cached != null && System.currentTimeMillis() < p.getLong("id_exp", 0L)) return Pair(cached, uid)
        val rt = p.getString("rt", null) ?: return null
        val (code, text) = http(
            "POST", "https://securetoken.googleapis.com/v1/token?key=$API_KEY",
            "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(rt, "UTF-8"), form = true
        )
        if (code != 200) {
            p.edit().putString("error", if (code == 400) "La sesión venció: vuelve a iniciar sesión" else "Sin conexión ($code)").apply()
            return null
        }
        val j = JSONObject(text)
        val id = j.optString("id_token", "")
        if (id.isEmpty()) return null
        val exp = System.currentTimeMillis() + (j.optString("expires_in", "3600").toLongOrNull() ?: 3600L) * 1000 - 120_000
        p.edit().putString("id", id).putLong("id_exp", exp)
            .putString("rt", j.optString("refresh_token", rt)).apply()
        return Pair(id, uid)
    }

    // ---------------------------------------------------------------- data

    private fun localXp(ctx: Context): Long {
        XpManager.init(ctx)
        return XpManager.totalXp + XpManager.pendingXp
    }

    private fun buildDoc(ctx: Context): JSONObject {
        Dictionary.get(ctx.filesDir).saveNow()
        TouchModel.save()
        val j = JSONObject()
        j.put("v", 1)
        j.put("t", System.currentTimeMillis())
        for (n in PREFS) j.put("p_$n", Backup.prefsText(ctx, n, LOCAL_ONLY))
        for (f in FILES) {
            val file = File(ctx.filesDir, f)
            if (file.exists() && file.length() < 400_000) j.put("f_" + f.removeSuffix(".txt"), file.readText())
        }
        return j
    }

    class Meta(val xp: Long, val level: Int, val time: Long, val device: String)

    /** What's saved in the cloud right now (null = nothing yet, or no connection: see [lastError]). */
    fun fetchMeta(ctx: Context): Meta? {
        val (id, uid) = idToken(ctx) ?: return null
        val (code, text) = http("GET", "$DB/teclado/$uid/meta.json?auth=$id", null)
        if (code != 200) { setError(ctx, code); return null }
        if (text.isBlank() || text == "null") return null
        val j = JSONObject(text)
        return Meta(j.optLong("xp", 0L), j.optLong("level", 1L).toInt(), j.optLong("t", 0L), j.optString("device", ""))
    }

    private fun setError(ctx: Context, code: Int) {
        prefs(ctx).edit().putString(
            "error", when (code) {
                401, 403 -> "Firebase no dio permiso: falta agregar la regla \"teclado\" en la base de datos"
                else -> "Error $code al conectar"
            }
        ).apply()
    }

    /**
     * Uploads the progress. Doesn't overwrite a cloud copy with more XP than this phone
     * (another phone went further) unless [force].
     */
    fun upload(ctx: Context, force: Boolean = false): Boolean {
        val app = ctx.applicationContext
        val (id, uid) = idToken(app) ?: return false
        val mine = localXp(app)
        if (!force) {
            val cloud = fetchMeta(app)
            if (cloud != null && cloud.xp > mine + 100) {
                prefs(app).edit().putString("error", "En la nube hay más progreso (nivel ${cloud.level}) que en este celular: usa \"Traer de la nube\"").apply()
                return false
            }
        }
        val doc = buildDoc(app)
        val (c1, _) = http("PUT", "$DB/teclado/$uid/data.json?auth=$id", doc.toString())
        if (c1 != 200) { setError(app, c1); return false }
        val meta = JSONObject()
        meta.put("xp", mine)
        meta.put("level", XpManager.level())
        meta.put("t", System.currentTimeMillis())
        meta.put("device", Build.MANUFACTURER + " " + Build.MODEL)
        val (c2, _) = http("PUT", "$DB/teclado/$uid/meta.json?auth=$id", meta.toString())
        if (c2 != 200) { setError(app, c2); return false }
        prefs(app).edit().putLong("last_up", System.currentTimeMillis()).remove("error").apply()
        return true
    }

    /** Uploads in the background when it's due (called when the keyboard closes). */
    fun autoUpload(ctx: Context, force: Boolean = false, done: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        if (!signedIn(app) || busy) return
        if (!force && System.currentTimeMillis() - lastUpload(app) < EVERY) return
        busy = true
        Thread {
            val ok = try {
                upload(app, force)
            } catch (e: java.io.IOException) {
                // no internet at that moment (or the phone blocked it in the background): try again next time
                prefs(app).edit().putString("error", "No había internet al intentar guardar; se vuelve a intentar solo").apply()
                false
            } catch (e: Exception) {
                CrashLog.log(e, "nube"); false
            } finally {
                busy = false
            }
            done?.invoke(ok)
        }.start()
    }

    /** Replaces this phone's progress with the cloud copy (then the app must restart). */
    fun download(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val (id, uid) = idToken(app) ?: return false
        val (code, text) = http("GET", "$DB/teclado/$uid/data.json?auth=$id", null)
        if (code != 200) { setError(app, code); return false }
        if (text.isBlank() || text == "null") return false
        val j = JSONObject(text)
        Dictionary.get(app.filesDir).frozen = true
        for (n in PREFS) {
            val t = j.optString("p_$n", "")
            if (t.isNotEmpty()) Backup.applyPrefs(app, n, t, LOCAL_ONLY)
        }
        for (f in FILES) {
            val t = j.optString("f_" + f.removeSuffix(".txt"), "")
            if (t.isNotEmpty()) File(app.filesDir, f).writeText(t)
        }
        prefs(app).edit().putLong("last_up", System.currentTimeMillis()).remove("error").apply()
        return true
    }
}
