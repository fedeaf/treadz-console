package com.treadz.console.treadmill.recording

/**
 * Minimal JSON (no dependencies) for the JSON Lines recordings. Writes `Map`, `List`, `String`, numbers,
 * `Boolean` and `null`; reads the same. Integers are read as `Long` and other numbers as `Double`.
 */
object Json {

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    /** Reads a complete JSON document. Throws [IllegalArgumentException] if it is not valid. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWhitespace()
        require(p.atEnd()) { "Trailing text after the JSON at position ${p.pos}" }
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("Not a JSON object: $text")

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(value)
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Double -> if (value.isNaN() || value.isInfinite()) sb.append("null") else sb.append(value.toString())
            is Float -> if (value.isNaN() || value.isInfinite()) sb.append("null") else sb.append(value.toString())
            is Number -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, v)
                }
                sb.append(']')
            }
            is Enum<*> -> writeString(sb, value.name)
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWhitespace()
            require(pos < s.length) { "Incomplete JSON" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Unexpected character '$c' at $pos")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, pos)) { "Expected $word at $pos" }
            pos += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWhitespace()
            if (s[pos] == '}') { pos++; return m }
            while (true) {
                skipWhitespace()
                val k = str()
                skipWhitespace()
                require(s[pos] == ':') { "Expected ':' at $pos" }
                pos++
                m[k] = value()
                skipWhitespace()
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw IllegalArgumentException("Expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            pos++ // [
            skipWhitespace()
            if (s[pos] == ']') { pos++; return l }
            while (true) {
                l += value()
                skipWhitespace()
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> throw IllegalArgumentException("Expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        private fun str(): String {
            require(s[pos] == '"') { "Expected '\"' at $pos" }
            pos++
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "Unterminated string" }
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLong()
        }
    }
}
