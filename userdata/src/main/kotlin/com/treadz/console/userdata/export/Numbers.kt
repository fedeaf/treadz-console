package com.treadz.console.userdata.export

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Locale-independent text formatting for the export files: never uses the default locale. */
internal object Formats {

    private val isoUtc: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    /** `2026-10-07T12:00:05.500Z`: UTC, millisecond precision, ASCII digits. */
    fun timestamp(epochMillis: Long): String = isoUtc.format(Instant.ofEpochMilli(epochMillis))

    /**
     * Plain decimal with a dot, rounded half up to at most [maxDecimals] places, without trailing zeros but with
     * at least one decimal (`8.0`, `8.35`). `null` for NaN and infinity.
     */
    fun decimal(value: Double, maxDecimals: Int): String? {
        if (!value.isFinite()) return null
        val text = BigDecimal.valueOf(value).setScale(maxDecimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if ('.' in text) text else "$text.0"
    }

    fun integer(value: Int): String = value.toString()
}
