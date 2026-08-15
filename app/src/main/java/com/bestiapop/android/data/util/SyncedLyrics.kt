package com.bestiapop.android.data.util

data class SyncedLyricLine(
    val timeMs: Long? = null,
    val text: String = ""
)

object SyncedLyrics {
    private val lrcLine = Regex("""\[(\d{2}):(\d{2})(?:\.(\d{1,3}))?\](.*)""")
    private val timestampOnly = Regex("""^(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?$""")

    fun parse(raw: String): List<SyncedLyricLine> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { lineStr ->
            val trimmed = lineStr.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val match = lrcLine.find(trimmed)
            if (match != null) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val frac = match.groupValues[3]
                val msPart = frac.toLongOrNull() ?: 0L
                val text = match.groupValues[4].trim()
                val totalMs = (min * 60 + sec) * 1000 + fractionalMs(frac, msPart)
                SyncedLyricLine(totalMs, text)
            } else {
                SyncedLyricLine(timeMs = null, text = trimmed)
            }
        }.toList()
    }

    fun format(lines: List<SyncedLyricLine>): String =
        lines.mapNotNull { line ->
            val text = line.text.trim()
            if (text.isEmpty() && line.timeMs == null) return@mapNotNull null
            val timeMs = line.timeMs
            if (timeMs != null) "[${formatTimestamp(timeMs)}]$text" else text.ifEmpty { null }
        }.joinToString("\n")

    fun plainText(lines: List<SyncedLyricLine>): String =
        lines.joinToString("\n") { it.text }

    fun looksLikeLrc(raw: String): Boolean =
        raw.lineSequence().any { lrcLine.containsMatchIn(it.trim()) }

    /**
     * Keep stamps when the user edits wording (same line count → by index).
     * If lines are inserted/removed, match leftover rows by exact text so a
     * newline at the top does not recycle the first stamp.
     */
    fun realignByText(
        old: List<SyncedLyricLine>,
        newTexts: List<String>
    ): List<SyncedLyricLine> {
        if (newTexts.isEmpty()) return emptyList()
        if (old.size == newTexts.size) {
            return newTexts.mapIndexed { i, text -> old[i].copy(text = text) }
        }
        val unused = old.toMutableList()
        return newTexts.map { text ->
            val idx = unused.indexOfFirst { it.text == text }
            if (idx >= 0) unused.removeAt(idx).copy(text = text)
            else SyncedLyricLine(timeMs = null, text = text)
        }
    }

    fun formatTimestamp(timeMs: Long): String {
        val clamped = timeMs.coerceAtLeast(0)
        val totalSec = clamped / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val cs = (clamped % 1000) / 10
        return "%02d:%02d.%02d".format(min, sec, cs)
    }

    fun parseTimestamp(raw: String): Long? {
        val match = timestampOnly.find(raw.trim()) ?: return null
        val min = match.groupValues[1].toLongOrNull() ?: return null
        val sec = match.groupValues[2].toLongOrNull() ?: return null
        if (sec >= 60) return null
        val frac = match.groupValues[3]
        val ms = fractionalMs(frac, frac.toLongOrNull() ?: 0L)
        return (min * 60 + sec) * 1000 + ms
    }

    /** 1 digit = tenths, 2 = centiseconds, 3 = milliseconds. */
    private fun fractionalMs(frac: String, value: Long): Long = when (frac.length) {
        0 -> 0L
        1 -> value * 100
        2 -> value * 10
        else -> value
    }

    fun stamp(line: SyncedLyricLine, timeMs: Long): SyncedLyricLine =
        line.copy(timeMs = timeMs.coerceAtLeast(0))

    fun hasTimestamps(lines: List<SyncedLyricLine>): Boolean =
        lines.any { it.timeMs != null }

    /** Last timed line whose stamp is ≤ [positionMs], or -1. */
    fun currentLineIndex(lines: List<SyncedLyricLine>, positionMs: Long): Int {
        var bestIdx = -1
        var bestTime = -1L
        for (i in lines.indices) {
            val t = lines[i].timeMs ?: continue
            if (t <= positionMs && t >= bestTime) {
                bestTime = t
                bestIdx = i
            }
        }
        return bestIdx
    }
}
