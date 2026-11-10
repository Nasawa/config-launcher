package dev.nasawa.configlauncher.config

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Strict pre-parse pass, iterative (no recursion, so hostile nesting can't overflow the stack):
 * strict UTF-8 (OQ-7/OQ-11), nesting depth ≤ [Limits.MAX_DEPTH] (OQ-9), duplicate keys (OQ-12),
 * NUL and lone surrogates in strings (OQ-11, §17 Q3), and bare tokens (#120): kotlinx's tree parser
 * takes any unquoted run (`00`, `-`, `.5`, `foo`) as a literal, so every token outside a string must
 * be an RFC 8259 number or `true`/`false`/`null`, as scripts/validate.py's json.loads requires.
 * The rest of the syntax is left to kotlinx. Returns a B1 error message or null.
 */
object JsonPreScan {
    /** RFC 8259 §6 number, ASCII digits only. */
    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    private const val SHOW = 40

    /** Null if [t] is a JSON number or literal, else the error (same text as validate.py's `bad_token`). */
    private fun badToken(t: String, line: Int): String? {
        if (t == "true" || t == "false" || t == "null" || NUMBER.matches(t)) return null
        val kind = if (t[0] in "+-.0123456789") "number" else "value"
        val shown = if (t.codePointCount(0, t.length) > SHOW) t.substring(0, t.offsetByCodePoints(0, SHOW)) + "…" else t
        return "invalid $kind \"$shown\" (line $line)"
    }

    private fun endsToken(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '"' || c == ',' || c == ':' ||
        c == '{' || c == '}' || c == '[' || c == ']'

    fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { null }

    /** [tokens] false skips the #120 token rule; only for last-good copies an older build saved (ConfigCodec.decodeLastGood). */
    fun check(text: String, tokens: Boolean = true): String? {
        // Stack of key sets; null entry = array.
        val stack = ArrayList<HashSet<String>?>()
        var i = 0
        var line = 1
        var expectKey = false
        val n = text.length
        while (i < n) {
            val c = text[i]
            when (c) {
                '\n' -> line++
                '{' -> { stack.add(HashSet()); if (stack.size > Limits.MAX_DEPTH) return "nested deeper than ${Limits.MAX_DEPTH} levels (line $line)"; expectKey = true }
                '[' -> { stack.add(null); if (stack.size > Limits.MAX_DEPTH) return "nested deeper than ${Limits.MAX_DEPTH} levels (line $line)"; expectKey = false }
                '}', ']' -> { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1); expectKey = false }
                ',' -> expectKey = stack.lastOrNull() != null
                ':' -> expectKey = false
                ' ', '\t', '\r' -> {}
                '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < n && text[i] != '"') {
                        var ch = text[i]
                        if (ch == '\\' && i + 1 < n) {
                            i++
                            ch = when (text[i]) {
                                'u' -> {
                                    val hex = text.substring(i + 1, minOf(i + 5, n))
                                    i += 4
                                    hex.toIntOrNull(16)?.toChar() ?: return "bad \\u escape (line $line)"
                                }
                                'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'b' -> '\b'; 'f' -> '\u000c'
                                else -> text[i]
                            }
                        }
                        if (ch == '\u0000') return "NUL character in a string (line $line)"
                        sb.append(ch)
                        i++
                    }
                    val s = sb.toString()
                    var k = 0
                    while (k < s.length) {
                        val ch = s[k]
                        if (Character.isHighSurrogate(ch)) { if (k + 1 >= s.length || !Character.isLowSurrogate(s[k + 1])) return "unpaired surrogate in a string (line $line)"; k++ }
                        else if (Character.isLowSurrogate(ch)) return "unpaired surrogate in a string (line $line)"
                        k++
                    }
                    if (expectKey) {
                        val keys = stack.lastOrNull()
                        if (keys != null && !keys.add(s)) return "duplicate key \"$s\" (line $line)"
                        expectKey = false
                    }
                }
                else -> {
                    val start = i
                    while (i + 1 < n && !endsToken(text[i + 1])) i++
                    if (tokens) badToken(text.substring(start, i + 1), line)?.let { return it }
                }
            }
            i++
        }
        return null
    }
}
