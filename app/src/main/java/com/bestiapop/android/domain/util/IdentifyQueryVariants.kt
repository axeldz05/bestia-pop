package com.bestiapop.android.domain.util

/**
 * Identify search variants: mixed-script titles (`Mirror 鏡子`) and latin+pinyin
 * leftovers (`Mirror Jing Zi`) without inventing romanization.
 */
object IdentifyQueryVariants {

    data class LetterRun(val text: String, val kind: Kind) {
        enum class Kind { LATIN, OTHER }
        val normalized: String get() = TrackMatchKeys.normalize(text)
    }

    fun expand(primary: String, filename: String? = null): List<String> {
        val cleaned = tidyFilenamePhrase(primary)
        if (cleaned.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>(8)
        out.add(cleaned)
        val glued = glueSingleLetterTokens(cleaned)
        if (glued != cleaned) out.add(glued)
        distinctiveSearchTail(cleaned)?.let(out::add)
        headAndTailSearch(cleaned)?.let(out::add)
        for (run in letterRuns(cleaned)) {
            if (run.text.equals(cleaned, ignoreCase = true)) continue
            val keep = run.kind == LetterRun.Kind.OTHER && run.text.isNotEmpty() ||
                run.text.length >= 3
            if (keep) out.add(run.text)
        }
        latinHeadDroppingPinyin(cleaned)?.let(out::add)
        pinyinTail(cleaned)?.let { tail ->
            if (tail.length >= 2) out.add(tail)
        }
        if (filename != null && looksLikeDiscTrackRip(filename)) {
            out.add("$cleaned soundtrack")
            out.add("$cleaned OST")
        }
        return out.filter { it.isNotBlank() }.take(6)
    }

    fun letterRuns(text: String): List<LetterRun> {
        if (text.isEmpty()) return emptyList()
        val runs = ArrayList<LetterRun>(4)
        val buf = StringBuilder()
        var kind: LetterRun.Kind? = null
        fun flush() {
            if (buf.isEmpty() || kind == null) {
                buf.clear()
                kind = null
                return
            }
            val chunk = buf.toString().trim()
            if (chunk.isNotEmpty()) runs.add(LetterRun(chunk, kind!!))
            buf.clear()
            kind = null
        }
        for (ch in text) {
            val next = letterKind(ch)
            if (next == null) {
                flush()
                continue
            }
            if (kind != null && next != kind) flush()
            kind = next
            buf.append(ch)
        }
        flush()
        return runs
    }

    fun latinHeadDroppingPinyin(text: String): String? {
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        if (tokens.size < 2) return null
        if (letterRuns(text).any { it.kind == LetterRun.Kind.OTHER }) return null
        var end = tokens.size
        while (end > 1 && isPinyinSyllable(tokens[end - 1])) end -= 1
        if (end == tokens.size) return null
        val head = tokens.take(end).joinToString(" ")
        return head.takeIf { it.isNotBlank() && !it.equals(text, ignoreCase = true) }
    }

    fun pinyinTail(text: String): String? {
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        if (tokens.size < 2) return null
        var start = tokens.size
        while (start > 0 && isPinyinSyllable(tokens[start - 1])) start -= 1
        if (start == 0 || start == tokens.size) return null
        return tokens.drop(start).joinToString(" ").takeIf { it.isNotBlank() }
    }

    fun hasHan(text: String): Boolean =
        text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

    fun hasOtherLetterScript(text: String): Boolean =
        letterRuns(text).any { it.kind == LetterRun.Kind.OTHER }

    fun hasLatinLetter(text: String): Boolean =
        letterRuns(text).any { it.kind == LetterRun.Kind.LATIN }

    fun latinLetters(text: String): String =
        letterRuns(text).filter { it.kind == LetterRun.Kind.LATIN }.joinToString(" ") { it.text }

    fun otherLetters(text: String): String =
        letterRuns(text).filter { it.kind == LetterRun.Kind.OTHER }.joinToString(" ") { it.text }

    fun searchTokens(raw: String): String {
        val runs = letterRuns(raw).joinToString(" ") { it.text }
        return if (runs.isBlank() || runs == raw) raw else "$raw $runs"
    }

    internal fun isPinyinSyllable(token: String): Boolean {
        val t = token.lowercase()
            .replace('ü', 'v')
            .replace('ū', 'u')
            .replace('ú', 'u')
            .replace('ǔ', 'u')
            .replace('ù', 'u')
        if (t.length !in 1..6) return false
        if (!t.all { it in 'a'..'z' }) return false
        return t in PINYIN_SYLLABLES
    }

    private fun letterKind(ch: Char): LetterRun.Kind? {
        if (!ch.isLetter()) return null
        return when (Character.UnicodeScript.of(ch.code)) {
            Character.UnicodeScript.LATIN -> LetterRun.Kind.LATIN
            Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> null
            else -> LetterRun.Kind.OTHER
        }
    }

    private val PINYIN_INITIALS = listOf(
        "", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
        "j", "q", "x", "zh", "ch", "sh", "r", "z", "c", "s", "y", "w"
    )
    private val PINYIN_FINALS = listOf(
        "a", "o", "e", "ai", "ei", "ao", "ou", "an", "en", "ang", "eng", "er",
        "i", "ia", "iao", "ie", "iu", "ian", "in", "iang", "ing", "iong",
        "u", "ua", "uo", "uai", "ui", "uan", "un", "uang", "ong",
        "v", "ve", "van", "vn"
    )
    private val PINYIN_SYLLABLES: Set<String> = buildSet {
        for (initial in PINYIN_INITIALS) {
            for (final in PINYIN_FINALS) add(initial + final)
        }
    }
}
