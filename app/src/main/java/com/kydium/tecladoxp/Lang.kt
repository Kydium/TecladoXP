package com.kydium.tecladoxp

import android.content.SharedPreferences
import java.util.Locale

/** Keyboard language: "auto" follows the phone's language (Spanish, English, or Spanish if it's another one). */
object Lang {
    fun system(): String = if (Locale.getDefault().language == "en") "en" else "es"

    fun resolve(raw: String?): String = when (raw) {
        "es", "en", "es_en" -> raw
        else -> system()
    }

    fun setting(p: SharedPreferences): String = resolve(p.getString("lang", "auto"))

    /** Once: people who had the old default (Spanish) now follow the phone's language. */
    fun migrate(p: SharedPreferences) {
        if (p.getBoolean("lang_v2", false)) return
        val e = p.edit().putBoolean("lang_v2", true)
        if ((p.getString("lang", "es") ?: "es") == "es") e.putString("lang", "auto")
        e.apply()
    }
}
