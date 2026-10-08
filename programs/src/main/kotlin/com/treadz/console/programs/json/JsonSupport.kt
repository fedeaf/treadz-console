package com.treadz.console.programs.json

import com.treadz.console.treadmill.recording.Json

/** A document could not be read: it is not JSON, or a field is missing or has the wrong type. [path] says where. */
class ProgramFormatException(val path: String, message: String) : IllegalArgumentException(if (path.isEmpty()) message else "$path: $message")

/** Parses [text] into maps, lists and scalars; every parser failure becomes a [ProgramFormatException]. */
internal fun parseDocument(text: String): Map<String, Any?> {
    val value = try {
        Json.parse(text)
    } catch (e: RuntimeException) {
        // The shared parser throws IllegalArgumentException, StringIndexOutOfBounds or NumberFormat for malformed text.
        throw ProgramFormatException("", "Not valid JSON: ${e.message}")
    }
    @Suppress("UNCHECKED_CAST")
    return value as? Map<String, Any?> ?: throw ProgramFormatException("", "The document must be a JSON object")
}

/** Typed, path-aware access to a parsed JSON object. Unknown keys are simply never asked for, so they are ignored. */
internal class JsonObject(private val map: Map<String, Any?>, val path: String) {

    private fun at(key: String) = if (path.isEmpty()) key else "$path.$key"

    fun has(key: String) = map[key] != null

    private fun fail(key: String, message: String): Nothing = throw ProgramFormatException(at(key), message)

    private fun required(key: String): Any = map[key] ?: fail(key, "is required")

    fun string(key: String): String = required(key) as? String ?: fail(key, "must be a string")

    fun optString(key: String): String? = if (map[key] == null) null else string(key)

    fun int(key: String): Int = toInt(key, required(key))

    fun optInt(key: String): Int? = if (map[key] == null) null else int(key)

    private fun toInt(key: String, v: Any): Int = when {
        v is Long && v in Int.MIN_VALUE..Int.MAX_VALUE -> v.toInt()
        v is Double && v == Math.rint(v) && v in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() -> v.toInt()
        else -> fail(key, "must be a whole number")
    }

    fun double(key: String): Double = toDouble(key, required(key))

    fun optDouble(key: String): Double? = if (map[key] == null) null else double(key)

    private fun toDouble(key: String, v: Any): Double = (v as? Number)?.toDouble() ?: fail(key, "must be a number")

    fun boolean(key: String, default: Boolean): Boolean = when (val v = map[key]) {
        null -> default
        is Boolean -> v
        else -> fail(key, "must be true or false")
    }

    fun obj(key: String): JsonObject = optObj(key) ?: fail(key, "is required")

    fun optObj(key: String): JsonObject? = when (val v = map[key]) {
        null -> null
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            JsonObject(v as Map<String, Any?>, at(key))
        }
        else -> fail(key, "must be an object")
    }

    /** A list whose elements are all objects. A missing key is an error unless [optional]. */
    fun objects(key: String, optional: Boolean = false): List<JsonObject> {
        val v = map[key] ?: if (optional) return emptyList() else fail(key, "is required")
        val list = v as? List<*> ?: fail(key, "must be a list")
        return list.mapIndexed { i, item ->
            @Suppress("UNCHECKED_CAST")
            JsonObject(item as? Map<String, Any?> ?: throw ProgramFormatException("${at(key)}[$i]", "must be an object"), "${at(key)}[$i]")
        }
    }

    /** An object whose values are all strings or numbers (numbers are kept as their text). */
    fun stringMap(key: String): Map<String, String> {
        val v = map[key] ?: return emptyMap()
        val m = v as? Map<*, *> ?: fail(key, "must be an object")
        return m.entries.associate { (k, value) ->
            k.toString() to when (value) {
                is String -> value
                is Number, is Boolean -> value.toString()
                else -> throw ProgramFormatException("${at(key)}.$k", "must be a string")
            }
        }
    }

    fun <E : Enum<E>> enum(key: String, values: Array<E>): E {
        val text = string(key)
        return values.firstOrNull { it.wireName() == text }
            ?: fail(key, "must be one of ${values.joinToString { "'${it.wireName()}'" }} (was '$text')")
    }

    fun <E : Enum<E>> optEnum(key: String, values: Array<E>): E? = if (map[key] == null) null else enum(key, values)
}

/** Name of an enum value in the JSON documents: lower camel case (`WarmUp` is `warmUp`). */
internal fun Enum<*>.wireName(): String = name.replaceFirstChar { it.lowercaseChar() }

/**
 * Renders a tree of maps, lists and scalars as readable JSON: two-space indentation, and any object or list that fits
 * on a line (about 130 columns) is kept on one line, so a block of a program is a single line when it can be.
 */
internal object JsonPretty {

    private const val WIDTH = 130

    /** [width]: an object or list whose one-line form fits in this many columns (with its indentation) stays on one line. */
    fun render(value: Any?, width: Int = WIDTH): String = StringBuilder().also { write(it, value, 0, width) }.append('\n').toString()

    private fun write(sb: StringBuilder, value: Any?, indent: Int, width: Int) {
        val compact = compact(value)
        if (value !is Map<*, *> && value !is List<*> || indent + compact.length <= width || isEmpty(value)) {
            sb.append(compact)
            return
        }
        val pad = " ".repeat(indent + 2)
        when (value) {
            is Map<*, *> -> {
                sb.append("{\n")
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(pad).append(string(k.toString())).append(": ")
                    write(sb, v, indent + 2, width)
                }
                sb.append('\n').append(" ".repeat(indent)).append('}')
            }
            is List<*> -> {
                sb.append("[\n")
                var first = true
                for (v in value) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(pad)
                    write(sb, v, indent + 2, width)
                }
                sb.append('\n').append(" ".repeat(indent)).append(']')
            }
        }
    }

    private fun isEmpty(v: Any?) = (v as? Map<*, *>)?.isEmpty() == true || (v as? List<*>)?.isEmpty() == true

    private fun compact(value: Any?): String = when (value) {
        null -> "null"
        is String -> string(value)
        is Boolean, is Int, is Long -> value.toString()
        is Double -> if (value.isFinite()) value.toString() else "null"
        is Enum<*> -> string(value.wireName())
        is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { (k, v) -> string(k.toString()) + ": " + compact(v) }
        is List<*> -> value.joinToString(", ", "[", "]") { compact(it) }
        else -> string(value.toString())
    }

    private fun string(s: String): String {
        val sb = StringBuilder("\"")
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
        return sb.append('"').toString()
    }
}
