package dev.nasawa.configlauncher.config

/** 1-based line and column (columns count UTF-16 units) in launcher.json. */
data class TextPos(val line: Int, val column: Int)

/**
 * Maps parse/validation error strings to a line and column. Runs a small position-tracking JSON
 * tokenizer, so it is only invoked when the error detail view is opened (docs/ux.md Q-S3).
 *
 * - `... (line N)` (pre-scan) → line N, column of the first non-blank character.
 * - `... at offset N ...` (kotlinx parser) → offset converted to line/column.
 * - `path.to[3].key: message` (validator) → position of that key; for a missing key, the deepest
 *   existing ancestor.
 */
object ErrorLocator {
    private val lineRe = Regex("""\(line (\d+)\)""")
    private val offsetRe = Regex("""at offset (\d+)""")
    private val pathRe = Regex("""^([A-Za-z_$][\w$-]*(?:\[\d+]|\.[A-Za-z_$][\w$-]*)*): """)
    private val segRe = Regex("""\[\d+]|\.?[A-Za-z_$][\w$-]*""")

    fun locate(text: String, errors: List<String>): List<TextPos?> {
        val positions by lazy { Tokenizer(text).run() }
        val lineStarts by lazy { lineStarts(text) }
        return errors.map { e ->
            lineRe.find(e)?.let { m ->
                val n = m.groupValues[1].toInt()
                val start = lineStarts.getOrNull(n - 1) ?: return@let null
                var c = start; while (c < text.length && (text[c] == ' ' || text[c] == '\t')) c++
                return@map TextPos(n, c - start + 1)
            }
            offsetRe.find(e)?.let { m -> return@map posOf(lineStarts, m.groupValues[1].toInt().coerceAtMost(text.length)) }
            val path = pathRe.find(e)?.groupValues?.get(1) ?: return@map null
            val segs = segRe.findAll(path).map { it.value.removePrefix(".").removeSurrounding("[", "]") }.toList()
            (segs.size downTo 0).firstNotNullOfOrNull { n -> positions[segs.take(n).joinToString("/")] }
                ?.let { posOf(lineStarts, it) }
        }
    }

    private fun lineStarts(t: String): IntArray {
        val out = ArrayList<Int>(); out += 0
        t.forEachIndexed { i, c -> if (c == '\n') out += i + 1 }
        return out.toIntArray()
    }

    private fun posOf(starts: IntArray, offset: Int): TextPos {
        var lo = 0; var hi = starts.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (starts[mid] <= offset) lo = mid else hi = mid - 1 }
        return TextPos(lo + 1, offset - starts[lo] + 1)
    }

    /** Lenient recursive-descent walk: path ("pages/0/items/1/package") → offset of the key (or array element). */
    private class Tokenizer(val s: String) {
        val out = HashMap<String, Int>()
        var i = 0
        fun run(): Map<String, Int> {
            runCatching { ws(); out[""] = i; value("", 0) }
            return out
        }
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(path: String, depth: Int) {
            if (depth > 64) error("deep")
            ws()
            when (s.getOrNull(i)) {
                '{' -> { i++; ws()
                    if (s.getOrNull(i) == '}') { i++; return }
                    while (true) {
                        ws(); val keyAt = i; val k = string()
                        val p = if (path.isEmpty()) k else "$path/$k"
                        out.putIfAbsent(p, keyAt)
                        ws(); if (s.getOrNull(i) != ':') error("colon"); i++
                        value(p, depth + 1); ws()
                        when (s.getOrNull(i)) { ',' -> i++; '}' -> { i++; return }; else -> error("obj") }
                    }
                }
                '[' -> { i++; ws()
                    if (s.getOrNull(i) == ']') { i++; return }
                    var n = 0
                    while (true) {
                        ws(); val p = if (path.isEmpty()) "$n" else "$path/$n"
                        out.putIfAbsent(p, i); value(p, depth + 1); n++; ws()
                        when (s.getOrNull(i)) { ',' -> i++; ']' -> { i++; return }; else -> error("arr") }
                    }
                }
                '"' -> string()
                null -> error("eof")
                else -> while (i < s.length && s[i] !in ",}] \t\r\n") i++
            }
        }
        fun string(): String {
            if (s.getOrNull(i) != '"') error("string")
            val sb = StringBuilder(); i++
            while (i < s.length && s[i] != '"') { if (s[i] == '\\') { i++; if (s.getOrNull(i) == 'u') { sb.append(s.substring(i + 1, minOf(i + 5, s.length)).toIntOrNull(16)?.toChar() ?: '?'); i += 4 } else sb.append(s.getOrNull(i) ?: ' ') } else sb.append(s[i]); i++ }
            i++
            return sb.toString()
        }
    }
}
