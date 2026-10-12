package com.treadz.console

import java.util.Locale

/** Decimal numbers typed by the user, accepted with a point or a comma whatever the language. */
object Decimals {
    fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    /** One decimal, with a decimal point like every number in the app. */
    fun format(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
}
