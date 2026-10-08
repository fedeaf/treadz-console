package com.treadz.console.sync.format

import java.math.BigDecimal

/**
 * Writes the JSON files of the repository: two-space indentation, fields in the order they are added, a final
 * newline. A fixed layout keeps the Git diffs small and readable for whoever analyses the data.
 */
internal class JsonObjectWriter {
    private class Field(val name: String, val text: String, val raw: Boolean)

    private val fields = ArrayList<Field>()

    fun put(name: String, value: String?): JsonObjectWriter = add(name, value?.let(::quote) ?: "null")

    fun put(name: String, value: Long?): JsonObjectWriter = add(name, value?.toString() ?: "null")

    fun put(name: String, value: Int?): JsonObjectWriter = add(name, value?.toString() ?: "null")

    fun put(name: String, value: Boolean): JsonObjectWriter = add(name, value.toString())

    fun put(name: String, value: Double?): JsonObjectWriter =
        add(name, if (value == null || !value.isFinite()) "null" else BigDecimal.valueOf(value).toPlainString())

    fun put(name: String, value: JsonObjectWriter): JsonObjectWriter = add(name, value.render(indent = 1))

    /** Adds [json], which must already be valid JSON, as is (the lines after the first are indented to the field). */
    fun putRaw(name: String, json: String): JsonObjectWriter {
        fields += Field(name, json.trim(), raw = true)
        return this
    }

    private fun add(name: String, rendered: String): JsonObjectWriter {
        fields += Field(name, rendered, raw = false)
        return this
    }

    fun toText(): String = render(indent = 0) + "\n"

    private fun render(indent: Int): String {
        if (fields.isEmpty()) return "{}"
        val pad = "  ".repeat(indent + 1)
        val closing = "  ".repeat(indent)
        return fields.joinToString(",\n", prefix = "{\n", postfix = "\n$closing}") { f ->
            val value = if (f.raw) f.text.lines().joinToString("\n$pad") else f.text
            "$pad${quote(f.name)}: $value"
        }
    }

    private fun quote(text: String): String {
        val out = StringBuilder("\"")
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append(String.format("\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }
}
