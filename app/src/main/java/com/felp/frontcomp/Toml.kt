package com.felp.frontcomp

/**
 * A small TOML reader, covering the subset these config files use.
 *
 * Written rather than pulled in because the files are meant to be edited by hand: when
 * something is wrong the useful thing is "line 14: unterminated string", not a stack
 * trace from a general-purpose parser. What is supported:
 *
 *   # comments
 *   [table]
 *   [[array of tables]]
 *   key = "basic string"        with \" \\ \n \t \r escapes
 *   key = 'literal string'      no escapes, taken as written
 *   key = 42 | 1.5 | true       numbers and booleans
 *   key = ["a", "b"]            arrays, on one line or spread over several
 *
 * Not supported, and rejected rather than misread: inline tables, dotted keys, dates and
 * multi-line strings.
 */
object Toml {

    class ParseError(val line: Int, message: String) :
        IllegalArgumentException("line $line: $message")

    /** A parsed value: string, boolean, number or list of those. */
    sealed interface Value {
        data class Str(val v: String) : Value
        data class Num(val v: Double) : Value
        data class Bool(val v: Boolean) : Value
        data class Arr(val v: List<Value>) : Value
    }

    /** One `[table]` or one `[[array of tables]]` entry. */
    class Table(val name: String, val values: Map<String, Value>) {
        fun string(key: String): String? = (values[key] as? Value.Str)?.v
        fun bool(key: String): Boolean? = (values[key] as? Value.Bool)?.v
        fun number(key: String): Double? = (values[key] as? Value.Num)?.v

        fun strings(key: String): List<String> = when (val v = values[key]) {
            is Value.Arr -> v.v.mapNotNull { (it as? Value.Str)?.v }
            is Value.Str -> listOf(v.v)
            else -> emptyList()
        }

        /** Para valores que son varias medidas a la vez, como un rectángulo. */
        fun numbers(key: String): List<Double> = when (val v = values[key]) {
            is Value.Arr -> v.v.mapNotNull { (it as? Value.Num)?.v }
            is Value.Num -> listOf(v.v)
            else -> emptyList()
        }
    }

    class Document(val tables: List<Table>) {
        /** All entries of one `[[name]]`, in file order. */
        fun all(name: String): List<Table> = tables.filter { it.name == name }
        fun first(name: String): Table? = tables.firstOrNull { it.name == name }
    }

    fun parse(text: String): Document {
        val tables = mutableListOf<Table>()
        var currentName: String? = null
        var current = LinkedHashMap<String, Value>()

        fun closeTable() {
            currentName?.let { tables += Table(it, current) }
            current = LinkedHashMap()
        }

        // Sin la marca de orden de bytes del principio: el Bloc de notas la pone al guardar en
        // UTF-8, trim() no la quita, y el fichero entero fallaba en la linea 1 con «expected
        // key = value» por algo que no se ve.
        val lines = text.removePrefix("﻿").lines()
        var i = 0
        while (i < lines.size) {
            val lineNo = i + 1
            val raw = lines[i]
            val line = stripComment(raw).trim()
            i++
            if (line.isEmpty()) continue

            // [[table]] and [table] both start a new table; the difference only matters to
            // the caller, which asks for entries by name and gets as many as there were.
            if (line.startsWith("[")) {
                val double = line.startsWith("[[")
                val close = if (double) "]]" else "]"
                if (!line.endsWith(close)) throw ParseError(lineNo, "unclosed table header")
                val name = line.removePrefix(if (double) "[[" else "[")
                    .removeSuffix(close).trim()
                if (name.isEmpty()) throw ParseError(lineNo, "empty table name")
                closeTable()
                currentName = name
                continue
            }

            val eq = line.indexOf('=')
            if (eq <= 0) throw ParseError(lineNo, "expected key = value")
            val key = line.substring(0, eq).trim()
            if (key.isEmpty()) throw ParseError(lineNo, "empty key")
            if (currentName == null) throw ParseError(lineNo, "value outside any table")

            var rest = line.substring(eq + 1).trim()

            // An array may keep going over several lines, so keep pulling them in until
            // the brackets balance. Doing this here means the value parser never has to
            // know about line breaks.
            if (rest.startsWith("[") && !bracketsBalanced(rest)) {
                val sb = StringBuilder(rest)
                while (i < lines.size && !bracketsBalanced(sb.toString())) {
                    sb.append(' ').append(stripComment(lines[i]).trim())
                    i++
                }
                if (!bracketsBalanced(sb.toString())) throw ParseError(lineNo, "unclosed array")
                rest = sb.toString()
            }

            current[key] = parseValue(rest, lineNo)
        }
        closeTable()
        return Document(tables)
    }

    /* ------------------------------------------------------------------ internals */

    /** Removes a trailing comment, leaving `#` alone when it is inside a string. */
    private fun stripComment(line: String): String {
        var inBasic = false
        var inLiteral = false
        var escaped = false
        for ((idx, c) in line.withIndex()) {
            when {
                escaped -> escaped = false
                c == '\\' && inBasic -> escaped = true
                c == '"' && !inLiteral -> inBasic = !inBasic
                c == '\'' && !inBasic -> inLiteral = !inLiteral
                c == '#' && !inBasic && !inLiteral -> return line.substring(0, idx)
            }
        }
        return line
    }

    private fun bracketsBalanced(s: String): Boolean {
        var depth = 0
        var inBasic = false
        var inLiteral = false
        var escaped = false
        for (c in s) {
            when {
                escaped -> escaped = false
                c == '\\' && inBasic -> escaped = true
                c == '"' && !inLiteral -> inBasic = !inBasic
                c == '\'' && !inBasic -> inLiteral = !inLiteral
                inBasic || inLiteral -> {}
                c == '[' -> depth++
                c == ']' -> depth--
            }
        }
        return depth == 0
    }

    private fun parseValue(text: String, lineNo: Int): Value {
        val s = text.trim()
        if (s.isEmpty()) throw ParseError(lineNo, "missing value")

        if (s.startsWith("[")) {
            if (!s.endsWith("]")) throw ParseError(lineNo, "unclosed array")
            val inner = s.substring(1, s.length - 1)
            return Value.Arr(splitArray(inner, lineNo).map { parseValue(it, lineNo) })
        }
        // Las de tres comillas no se leen: rechazadas con su nombre, como dice la cabecera. Se
        // leian de una linea con las comillas de mas dentro, y en varias fallaban luego en la
        // linea siguiente con un mensaje que no tenia que ver.
        if (s.startsWith("\"\"\"") || s.startsWith("'''")) {
            throw ParseError(lineNo, "multi-line strings are not supported: use one line with \\n")
        }
        if (s.startsWith("\"")) {
            if (s.length < 2 || !s.endsWith("\"")) throw ParseError(lineNo, "unterminated string")
            return Value.Str(unescape(s.substring(1, s.length - 1)))
        }
        if (s.startsWith("'")) {
            if (s.length < 2 || !s.endsWith("'")) throw ParseError(lineNo, "unterminated string")
            return Value.Str(s.substring(1, s.length - 1))
        }
        if (s == "true") return Value.Bool(true)
        if (s == "false") return Value.Bool(false)

        // Numbers may carry underscores for readability, as TOML allows.
        s.replace("_", "").toDoubleOrNull()?.let { return Value.Num(it) }
        throw ParseError(lineNo, "cannot read value: $s")
    }

    /** Splits on commas that are not inside a string or a nested array. */
    private fun splitArray(inner: String, lineNo: Int): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        var inBasic = false
        var inLiteral = false
        var escaped = false
        for (c in inner) {
            when {
                escaped -> { sb.append(c); escaped = false }
                c == '\\' && inBasic -> { sb.append(c); escaped = true }
                c == '"' && !inLiteral -> { inBasic = !inBasic; sb.append(c) }
                c == '\'' && !inBasic -> { inLiteral = !inLiteral; sb.append(c) }
                inBasic || inLiteral -> sb.append(c)
                c == '[' -> { depth++; sb.append(c) }
                c == ']' -> { depth--; sb.append(c) }
                c == ',' && depth == 0 -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
        }
        if (inBasic || inLiteral) throw ParseError(lineNo, "unterminated string in array")
        // A trailing comma is legal and leaves an empty tail, which is not an element.
        sb.toString().takeIf { it.isNotBlank() }?.let { out += it }
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun unescape(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.lastIndex) { append(c); i++; continue }
            when (val n = s[i + 1]) {
                'n' -> append('\n')
                't' -> append('\t')
                'r' -> append('\r')
                '"' -> append('"')
                '\\' -> append('\\')
                else -> {
                    // Un escape que no reconocemos se deja tal cual en vez de perderse:
                    // es más fácil de ver en pantalla que de depurar a ciegas.
                    append('\\')
                    append(n)
                }
            }
            i += 2
        }
    }
}
