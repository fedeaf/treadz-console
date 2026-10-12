package com.treadz.console.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Formatting of the figures. Numbers always use a decimal point, whatever the language (the user's choice); dates
 * follow the system language.
 */
object Format {
    const val NO_VALUE = "—"

    fun speed(kmh: Double): String = number("%.1f", kmh)

    /** Whole percent: the treadmill moves in steps of 1 %, so "3" and not "3.0". */
    fun incline(percent: Double): String = number("%d", percent.roundToInt())

    fun distanceKm(meters: Int): String = number("%.2f", meters / 1000.0)

    fun duration(totalSeconds: Int): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) number("%d:%02d:%02d", h, m, sec) else number("%02d:%02d", m, sec)
    }

    fun calories(kcal: Int): String = number("%d", kcal)

    fun heartRate(bpm: Int?): String = if (bpm == null) NO_VALUE else number("%d", bpm)

    /** Weight with one decimal, e.g. `68.5`. */
    fun weight(kg: Double): String = number("%.1f", kg)

    /** Heart rate, calories, … as a whole number. */
    fun whole(value: Int): String = number("%d", value)

    /** [format] with a decimal point and plain digits. */
    fun number(format: String, vararg args: Any): String = String.format(Locale.ROOT, format, *args)

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
