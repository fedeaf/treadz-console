package com.treadz.console.ui

import java.util.Locale

/** Formatting of the live figures. Always in the system language (decimal comma in Spanish). */
object Format {
    const val NO_VALUE = "—"

    fun speed(kmh: Double, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.1f", kmh)

    fun incline(percent: Double, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.1f", percent)

    fun distanceKm(meters: Int, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.2f", meters / 1000.0)

    fun duration(totalSeconds: Int, locale: Locale = Locale.getDefault()): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(locale, "%d:%02d:%02d", h, m, sec) else String.format(locale, "%02d:%02d", m, sec)
    }

    fun calories(kcal: Int, locale: Locale = Locale.getDefault()): String = String.format(locale, "%d", kcal)

    fun heartRate(bpm: Int?, locale: Locale = Locale.getDefault()): String =
        if (bpm == null) NO_VALUE else String.format(locale, "%d", bpm)
}
