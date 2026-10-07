package com.treadz.console.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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

    /** Weight with one decimal, e.g. `68.5`. */
    fun weight(kg: Double, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.1f", kg)

    /** Heart rate, calories, … as a whole number. */
    fun whole(value: Int, locale: Locale = Locale.getDefault()): String = String.format(locale, "%d", value)

    fun date(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)

    /** Date and time of a moment, in the device time zone. */
    fun dateTime(epochMillis: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
            .format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** Short date for chart axes: day and month (e.g. `7 Oct`), with the year when [withYear] (long ranges). */
    fun axisDate(date: LocalDate, withYear: Boolean, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern(if (withYear) "d MMM yy" else "d MMM", locale).format(date)
}
