package dev.nasawa.configlauncher.ui

/** OQ-11: strip bidi controls, zero-width and other Cf/Cc characters from labels at render time. */
object Labels {
    fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val t = Character.getType(cp)
            if (t != Character.FORMAT.toInt() && t != Character.CONTROL.toInt()) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString().trim().ifEmpty { "?" }
    }
}
