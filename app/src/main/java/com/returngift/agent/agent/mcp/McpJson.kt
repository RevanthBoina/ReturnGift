// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.mcp

/**
 * Minimal, dependency-free JSON for the MCP protocol layer.
 *
 * Why not Gson? The protocol layer is deliberately **Android-free and
 * dependency-free** so it can be unit-tested on a plain JVM and reused by the
 * knowledge connectors. MCP payloads are small and well-formed; a ~200-line
 * value model plus parser is cheaper (and more predictable) than widening the
 * contract of a serialization library into a pure-Kotlin module.
 *
 * Behaviour is strict on purpose: malformed input throws [McpJsonException], and
 * callers translate that into a protocol error instead of guessing.
 */
sealed class JsonValue {
    data class Obj(val fields: Map<String, JsonValue> = emptyMap()) : JsonValue() {
        operator fun get(key: String): JsonValue? = fields[key]
        fun str(key: String): String? = (fields[key] as? Str)?.value
        fun num(key: String): Double? = (fields[key] as? Num)?.value
        fun int(key: String): Int? = (fields[key] as? Num)?.value?.toInt()
        fun bool(key: String): Boolean? = (fields[key] as? Bool)?.value
        fun obj(key: String): Obj? = fields[key] as? Obj
        fun arr(key: String): Arr? = fields[key] as? Arr
    }
    data class Arr(val items: List<JsonValue> = emptyList()) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data object Null : JsonValue()

    fun toJson(): String = Json.write(this)
}

class McpJsonException(message: String) : Exception(message)

object Json {

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.readValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw McpJsonException("trailing content at ${parser.pos}")
        return value
    }

    fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(pairs.toMap())

    fun str(value: String?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Str(value)

    fun num(value: Int): JsonValue = JsonValue.Num(value.toDouble())

    fun bool(value: Boolean): JsonValue = JsonValue.Bool(value)

    fun write(value: JsonValue): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonValue.Obj -> {
                out.append('{')
                value.fields.entries.forEachIndexed { index, (key, child) ->
                    if (index > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    write(child, out)
                }
                out.append('}')
            }
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, child ->
                    if (index > 0) out.append(',')
                    write(child, out)
                }
                out.append(']')
            }
            is JsonValue.Str -> writeString(value.value, out)
            is JsonValue.Num -> {
                val d = value.value
                if (d == d.toLong().toDouble()) out.append(d.toLong()) else out.append(d)
            }
            is JsonValue.Bool -> out.append(value.value)
            JsonValue.Null -> out.append("null")
        }
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        value.forEach { c ->
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    private class Parser(val text: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun readValue(): JsonValue {
            skipWhitespace()
            if (atEnd()) throw McpJsonException("unexpected end of input")
            return when (val c = text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't', 'f' -> readBoolean()
                'n' -> readNull()
                else -> if (c == '-' || c.isDigit()) readNumber() else throw McpJsonException("unexpected '$c' at $pos")
            }
        }

        private fun readObject(): JsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') { pos++; return JsonValue.Obj(fields) }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                fields[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return JsonValue.Obj(fields) }
                    else -> throw McpJsonException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): JsonValue.Arr {
            expect('[')
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') { pos++; return JsonValue.Arr(items) }
            while (true) {
                items += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return JsonValue.Arr(items) }
                    else -> throw McpJsonException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw McpJsonException("unterminated string")
                when (val c = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw McpJsonException("unterminated escape")
                        when (val esc = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw McpJsonException("bad \\u escape")
                                val hex = text.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: throw McpJsonException("bad \\u escape"))
                            }
                            else -> throw McpJsonException("bad escape '\\$esc'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): JsonValue.Num {
            val start = pos
            if (peek() == '-') pos++
            while (!atEnd() && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val raw = text.substring(start, pos)
            return JsonValue.Num(raw.toDoubleOrNull() ?: throw McpJsonException("bad number '$raw'"))
        }

        private fun readBoolean(): JsonValue.Bool = when {
            text.startsWith("true", pos) -> { pos += 4; JsonValue.Bool(true) }
            text.startsWith("false", pos) -> { pos += 5; JsonValue.Bool(false) }
            else -> throw McpJsonException("bad literal at $pos")
        }

        private fun readNull(): JsonValue {
            if (!text.startsWith("null", pos)) throw McpJsonException("bad literal at $pos")
            pos += 4
            return JsonValue.Null
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else text[pos]

        private fun expect(c: Char) {
            if (atEnd() || text[pos] != c) throw McpJsonException("expected '$c' at $pos")
            pos++
        }
    }
}
