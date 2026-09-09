package com.gato.client.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit

/**
 * Central, configurable accent palette for the whole client (app + overlay).
 *
 * Default is the pastel pink pair from the PC client (secondColor 249,168,212
 * with a lighter companion). The accent is selectable from the Settings page
 * and persisted in SharedPreferences.
 */
object ColorTheme {

    // Presets offered in Settings — pastel pinks first (the client's identity)
    val presets: List<Pair<String, Color>> = listOf(
        "Rosa Pastel" to Color(249, 168, 212),
        "Rosa Claro" to Color(255, 193, 218),
        "Lavanda" to Color(200, 170, 230),
        "Violeta" to Color(168, 85, 247),
        "Oceano" to Color(115, 145, 255),
        "Hielo" to Color(130, 215, 240),
        "Esmeralda" to Color(60, 200, 120),
        "Atardecer" to Color(255, 138, 76),
        "Lava" to Color(255, 80, 60),
        "Monocromo" to Color(200, 200, 200),
    )

    private const val PREFS_NAME = "theme_settings"
    private const val KEY_ACCENT = "accent_argb"
    private const val KEY_ACCENT_SOFT = "accent_soft_argb"

    var accent by mutableStateOf(Color(249, 168, 212))
        private set

    var accentSoft by mutableStateOf(Color(252, 208, 232))
        private set

    private var prefs: SharedPreferences? = null
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val p = prefs ?: return
        val savedAccent = p.getInt(KEY_ACCENT, Int.MIN_VALUE)
        if (savedAccent != Int.MIN_VALUE) {
            accent = Color(savedAccent)
            accentSoft = Color(p.getInt(KEY_ACCENT_SOFT, lighten(accent, 0.45f).toArgb()))
        }
    }

    fun applyAccent(color: Color) {
        accent = color
        accentSoft = lighten(color, 0.45f)
        prefs?.edit {
            putInt(KEY_ACCENT, accent.toArgb())
            putInt(KEY_ACCENT_SOFT, accentSoft.toArgb())
        }
    }

    /** Blend toward white: 0 = color, 1 = white. */
    fun lighten(color: Color, fraction: Float): Color {
        val f = fraction.coerceIn(0f, 1f)
        fun ch(c: Float) = c + (1f - c) * f
        return Color(ch(color.red), ch(color.green), ch(color.blue), color.alpha)
    }
}
