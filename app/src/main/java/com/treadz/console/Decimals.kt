package com.treadz.console

import java.util.Locale

/** Decimal numbers typed by the user, accepted with a point or a comma whatever the language. */
object Decimals {
    fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    /** One decimal, in the system language (the field shows what the user is used to read). */
    fun format(value: Double, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.1f", value)
}
