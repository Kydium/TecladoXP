package com.kydium.tecladoxp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-updater: every build on GitHub Actions is published as a release
 * (tag "build-<number>", asset TecladoXP.apk). The app compares that number with
 * its own versionCode, downloads the APK and installs it over itself.
 */
object Updater {
    private const val API = "https://api.github.com/repos/Kydium/TecladoXP/releases/latest"

    class Release(val code: Long, val name: String, val url: String, val size: Long, val notes: String)

    fun currentCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
    }

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Blocking. Returns null if there is no release or no connection. */
    fun fetchLatest(): Release? {
        val con = URL(API).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 10_000
            con.readTimeout = 15_000
            con.setRequestProperty("Accept", "application/vnd.github+json")
            con.setRequestProperty("User-Agent", "TecladoXP")
            if (con.responseCode != 200) return null
            val body = con.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(body)
            val tag = j.optString("tag_name", "")
            val code = tag.filter { it.isDigit() }.toLongOrNull() ?: return null
            val assets = j.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Release(
                        code,
                        j.optString("name", tag),
                        a.optString("browser_download_url"),
                        a.optLong("size", 0),
                        j.optString("body", "")
                    )
                }
            }
            return null
        } finally {
            con.disconnect()
        }
    }

    /** Blocking download with progress 0..100. */
    fun download(rel: Release, dest: File, progress: (Int) -> Unit): Boolean {
        var url = rel.url
        repeat(5) {
            val con = URL(url).openConnection() as HttpURLConnection
            con.instanceFollowRedirects = true
            con.connectTimeout = 15_000
            con.readTimeout = 30_000
            con.setRequestProperty("User-Agent", "TecladoXP")
            try {
                val rc = con.responseCode
                if (rc in 300..399) {
                    url = con.getHeaderField("Location") ?: return false
                    return@repeat
                }
                if (rc != 200) return false
                val total = if (con.contentLength > 0) con.contentLength.toLong() else rel.size
                con.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            done += r
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != last) { last = p; progress(p) }
                            }
                        }
                    }
                }
                return true
            } finally {
                con.disconnect()
            }
        }
        return false
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Hands the APK to the system installer (shows the "update?" confirmation). */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("TecladoXP.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            session.commit(pi.intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error desconocido"
                Toast.makeText(context, "No se pudo actualizar: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
