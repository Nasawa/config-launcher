package dev.nasawa.configlauncher.config

/**
 * #79 title-bar templates (title-bar.md §5, ux.md §15.1): literal text, `{{` / `}}` for braces, and tokens
 * `{page}`, `{pageNumber}`, `{pageCount}`, `{time[:pattern]}`, `{date[:pattern]}`. Our own tokenizer: patterns pass
 * an allow-list (and per-letter counts) before they ever reach DateTimeFormatter, so rendering can't throw.
 * The bare names `pageTitle`, `clock` and `date` are shorthands for `{page}`, `{time}` and `{date}` (Pragma's #80 slots).
 * Positions are 1-based code points. `scripts/validate.py` mirrors this.
 */
object Template {
    val NAMES = listOf("page", "pageNumber", "pageCount", "time", "date")
    val SHORTHANDS = mapOf("pageTitle" to "page", "clock" to "time", "date" to "date")
    /** Letters allowed in a pattern and the most of each in a row (more makes DateTimeFormatter throw). */
    val LETTERS = mapOf('y' to 19, 'M' to 5, 'd' to 2, 'E' to 5, 'a' to 1, 'H' to 2, 'h' to 2, 'k' to 2, 'K' to 2, 'm' to 2)
    const val PUNCTUATION = " .,:/-"

    sealed interface Part
    data class Lit(val text: String) : Part
    data class Tok(val name: String, val pattern: String? = null) : Part

    sealed interface Problem {
        data class TooLong(val length: Int) : Problem
        data object MultiLine : Problem
        data class Unknown(val name: String) : Problem
        data class NoPattern(val token: String) : Problem
        data class EmptyPattern(val name: String) : Problem
        data class PatternLong(val name: String, val length: Int) : Problem
        data class Letter(val bad: String, val token: String) : Problem
        /** A letter repeated more often than DateTimeFormatter allows ([max] letters at most). */
        data class RunTooLong(val run: String, val max: Int) : Problem
        data object QuoteUnclosed : Problem
        data class Unclosed(val at: Int) : Problem
        data class Stray(val at: Int) : Problem
        data class TooMany(val count: Int) : Problem
    }

    fun message(p: Problem, path: String): String = when (p) {
        is Problem.TooLong -> "$path is ${p.length} characters; the maximum is ${TitleBar.MAX_TEMPLATE}."
        Problem.MultiLine -> "$path: A template is one line. Remove the line break."
        is Problem.Unknown -> "$path: unknown token {${p.name}}. Use page, pageNumber, pageCount, time or date."
        is Problem.NoPattern -> "$path: ${p.token} doesn't take a pattern. Only {time} and {date} do."
        is Problem.EmptyPattern -> "$path: {${p.name}:} has an empty pattern. Write {${p.name}} for the default format."
        is Problem.PatternLong -> "$path: the pattern in {${p.name}:…} is ${p.length} characters; the maximum is ${TitleBar.MAX_PATTERN}."
        is Problem.RunTooLong -> "$path: ${p.run} is too long. Use at most ${p.max}, like ${p.run.take(p.max)}."
        Problem.QuoteUnclosed -> "$path: A ' quote isn't closed. Add a closing ' after the text."
        is Problem.Letter -> "$path: \"${p.bad}\" isn't allowed in ${p.token}. Use y M d E a H h k K m; seconds and time zones aren't supported."
        is Problem.Unclosed -> "$path: the \"{\" at character ${p.at} isn't closed. For a literal brace, write {{."
        is Problem.Stray -> "$path: the \"}\" at character ${p.at} has no opening \"{\". For a literal brace, write }}."
        is Problem.TooMany -> "$path has ${p.count} tokens; the maximum is ${TitleBar.MAX_TOKENS}."
    }

    sealed interface Result
    data class Ok(val parts: List<Part>) : Result
    data class Bad(val problem: Problem) : Result

    fun check(s: String): Problem? = (parse(s) as? Bad)?.problem

    fun parse(s: String): Result {
        SHORTHANDS[s]?.let { return Ok(listOf(Tok(it))) }
        val cps = s.codePoints().toArray()
        if (cps.size > TitleBar.MAX_TEMPLATE) return Bad(Problem.TooLong(cps.size))
        if ('\n'.code in cps || '\r'.code in cps) return Bad(Problem.MultiLine)
        val parts = mutableListOf<Part>()
        val lit = StringBuilder()
        var tokens = 0
        var i = 0
        fun str(from: Int, to: Int) = String(cps, from, to - from)
        while (i < cps.size) {
            val c = cps[i]
            when {
                c == '{'.code && cps.getOrNull(i + 1) == '{'.code -> { lit.append('{'); i += 2 }
                c == '}'.code && cps.getOrNull(i + 1) == '}'.code -> { lit.append('}'); i += 2 }
                c == '}'.code -> return Bad(Problem.Stray(i + 1))
                c == '{'.code -> {
                    var j = i + 1
                    while (j < cps.size && cps[j] != '}'.code) j++
                    if (j >= cps.size) return Bad(Problem.Unclosed(i + 1))
                    val inner = str(i + 1, j)
                    val colon = inner.indexOf(':')
                    val name = if (colon < 0) inner else inner.substring(0, colon)
                    val pattern = if (colon < 0) null else inner.substring(colon + 1)
                    if (name !in NAMES) return Bad(Problem.Unknown(name))
                    if (pattern != null) {
                        if (name != "time" && name != "date") return Bad(Problem.NoPattern("{$inner}"))
                        if (pattern.isEmpty()) return Bad(Problem.EmptyPattern(name))
                        val len = pattern.codePointCount(0, pattern.length)
                        if (len > TitleBar.MAX_PATTERN) return Bad(Problem.PatternLong(name, len))
                        badInPattern(pattern)?.let { bad ->
                            return Bad(when {
                                bad == "'" -> Problem.QuoteUnclosed
                                bad.length > 1 || (bad[0] in LETTERS) -> Problem.RunTooLong(bad, LETTERS.getValue(bad[0]))
                                else -> Problem.Letter(bad, "{$inner}")
                            })
                        }
                    }
                    if (lit.isNotEmpty()) { parts += Lit(lit.toString()); lit.clear() }
                    parts += Tok(name, pattern)
                    tokens++
                    i = j + 1
                }
                else -> { lit.appendCodePoint(c); i++ }
            }
        }
        if (lit.isNotEmpty()) parts += Lit(lit.toString())
        if (tokens > TitleBar.MAX_TOKENS) return Bad(Problem.TooMany(tokens))
        return Ok(parts)
    }

    /** The first disallowed letter, too-long run of an allowed letter, or character in [p], or null. An unclosed quote reports `'`. */
    fun badInPattern(p: String): String? {
        var i = 0
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\'' -> {
                    if (p.getOrNull(i + 1) == '\'') { i += 2; continue }
                    var j = i + 1
                    while (true) {
                        if (j >= p.length) return "'"
                        if (p[j] == '\'') { if (p.getOrNull(j + 1) == '\'') { j += 2; continue }; break }
                        j++
                    }
                    i = j + 1
                }
                c in LETTERS -> {
                    var j = i
                    while (j < p.length && p[j] == c) j++
                    if (j - i > LETTERS.getValue(c)) return p.substring(i, j)
                    i = j
                }
                c in PUNCTUATION -> i++
                else -> return String(Character.toChars(p.codePointAt(i)))
            }
        }
        return null
    }

    /** Expands [parts]; [token] renders one token. */
    fun expand(parts: List<Part>, token: (Tok) -> String): String =
        parts.joinToString("") { when (it) { is Lit -> it.text; is Tok -> token(it) } }
}
