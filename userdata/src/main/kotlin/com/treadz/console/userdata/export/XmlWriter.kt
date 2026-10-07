package com.treadz.console.userdata.export

import java.io.Writer

/**
 * Minimal streaming XML writer: escapes text and attribute values, drops characters that XML 1.0 cannot carry,
 * indents with spaces and fails if elements are left open. Pure Kotlin so it runs on the JVM and on Android.
 */
internal class XmlWriter(private val out: Writer, private val indent: String = "  ") {

    private val open = ArrayDeque<String>()
    private var pendingStart = false
    private var hasChildren = false

    fun declaration() {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    }

    fun start(name: String, attributes: List<Pair<String, String>> = emptyList()) {
        closePendingStart(newLine = true)
        if (open.isNotEmpty()) out.write(indent.repeat(open.size))
        out.write("<$name")
        for ((k, v) in attributes) out.write(" $k=\"${escape(v, attribute = true)}\"")
        open.addLast(name)
        pendingStart = true
        hasChildren = false
    }

    fun end() {
        val name = open.removeLastOrNull() ?: error("No open element")
        if (pendingStart) {
            out.write("/>\n")
            pendingStart = false
        } else {
            if (hasChildren) out.write(indent.repeat(open.size))
            out.write("</$name>\n")
        }
        hasChildren = true
    }

    /** `<name attributes>text</name>` on one line. */
    fun element(name: String, text: String, attributes: List<Pair<String, String>> = emptyList()) {
        closePendingStart(newLine = true)
        out.write(indent.repeat(open.size))
        out.write("<$name")
        for ((k, v) in attributes) out.write(" $k=\"${escape(v, attribute = true)}\"")
        out.write(">${escape(text, attribute = false)}</$name>\n")
        hasChildren = true
    }

    fun finish() {
        check(open.isEmpty()) { "Unclosed elements: $open" }
        out.flush()
    }

    private fun closePendingStart(newLine: Boolean) {
        if (pendingStart) {
            out.write(if (newLine) ">\n" else ">")
            pendingStart = false
        }
        hasChildren = true
    }

    companion object {
        fun escape(text: String, attribute: Boolean): String {
            val sb = StringBuilder(text.length + 8)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '&' -> sb.append("&amp;")
                    c == '<' -> sb.append("&lt;")
                    c == '>' -> sb.append("&gt;")
                    c == '"' && attribute -> sb.append("&quot;")
                    c == '\n' && attribute -> sb.append("&#10;")
                    c == '\r' -> sb.append("&#13;")
                    c == '\t' && attribute -> sb.append("&#9;")
                    c == '\t' || c == '\n' -> sb.append(c)
                    c.code < 0x20 -> Unit // not allowed in XML 1.0
                    Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                        sb.append(c).append(text[i + 1])
                        i++
                    }
                    Character.isSurrogate(c) || c == '￾' || c == '￿' -> Unit // unpaired surrogate, non-characters
                    else -> sb.append(c)
                }
                i++
            }
            return sb.toString()
        }
    }
}
