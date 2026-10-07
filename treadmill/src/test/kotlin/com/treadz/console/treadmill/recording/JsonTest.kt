package com.treadz.console.treadmill.recording

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JsonTest {
    @Test
    fun `writes and reads back basic types`() {
        val src = linkedMapOf(
            "t" to 1791397803100L, "type" to "data", "speed" to 5.5, "n" to null, "ok" to true,
            "list" to listOf(1, 2.5, "x", null), "nested" to mapOf("a" to 1),
        )
        val text = Json.stringify(src)
        assertEquals("""{"t":1791397803100,"type":"data","speed":5.5,"n":null,"ok":true,"list":[1,2.5,"x",null],"nested":{"a":1}}""", text)
        val back = Json.parseObject(text)
        assertEquals(1791397803100L, back["t"])
        assertEquals(5.5, back["speed"])
        assertNull(back["n"])
        assertEquals(listOf<Any?>(1L, 2.5, "x", null), back["list"])
    }

    @Test
    fun `escapes quotes, line breaks and control characters without breaking the single line format`() {
        val s = "line1\nline2 \"quote\" \\ tab\t \u0001 ñ → €"
        val text = Json.stringify(mapOf("text" to s))
        assertEquals(false, text.contains('\n'))
        assertEquals(s, Json.parseObject(text)["text"])
    }

    @Test
    fun `NaN and infinity are written as null`() {
        assertEquals("""{"a":null,"b":null}""", Json.stringify(mapOf("a" to Double.NaN, "b" to Double.POSITIVE_INFINITY)))
    }

    @Test
    fun `rejects invalid json`() {
        assertThrows(IllegalArgumentException::class.java) { Json.parse("{\"a\":") }
        assertThrows(IllegalArgumentException::class.java) { Json.parse("{} x") }
        assertThrows(IllegalArgumentException::class.java) { Json.parseObject("[1]") }
    }

    @Test
    fun `negative numbers and exponents`() {
        assertEquals(-3L, Json.parse("-3"))
        assertEquals(1.5e-4, Json.parse("1.5E-4"))
    }
}
