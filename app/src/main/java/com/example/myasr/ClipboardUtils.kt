package com.example.myasr

object ClipboardUtils {

    /**
     * Formats raw clipboard text into a compact single-line preview for CandidateStripView chip.
     * Replaces newlines and consecutive whitespace with a single space, and trims to maxLength.
     * E.g.
     *  - "こんにちは、世界" -> "📋 こんにちは、世界"
     *  - "https://example.com/very/long/path" -> "📋 https://example.co…"
     */
    fun formatCandidateChipPreview(rawText: String?, maxChars: Int = 16): String {
        return formatClipboardPreview(rawText, maxChars)
    }

    /**
     * Compact preview with default 3 chars for candidate strip icon/chip.
     */
    fun formatClipboardPreview(rawText: String?, maxChars: Int = 3): String {
        if (rawText.isNullOrBlank()) {
            return "📋"
        }
        val clean = rawText.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) {
            return "📋"
        }
        return if (clean.length > maxChars) {
            "📋 " + clean.take(maxChars) + "…"
        } else {
            "📋 " + clean
        }
    }

    /**
     * Formats raw clipboard text for display in the history list item.
     * Preserves visual structure up to maxLines / maxChars, replacing newlines with visual arrow ↵.
     */
    fun formatHistoryItemPreview(rawText: String?, maxChars: Int = 120): String {
        if (rawText.isNullOrBlank()) {
            return ""
        }
        val sanitized = rawText.replace("\r\n", "\n").replace("\r", "\n")
            .split("\n")
            .filter { it.isNotBlank() }
            .joinToString(" ↵ ") { it.trim() }

        return if (sanitized.length > maxChars) {
            sanitized.take(maxChars) + "…"
        } else {
            sanitized
        }
    }

    /**
     * Formats elapsed time since discovery (in milliseconds) into a friendly Japanese relative string.
     */
    fun formatElapsedTime(elapsedMs: Long, isCurrentOsClip: Boolean): String {
        if (isCurrentOsClip) {
            return "現在のクリップ"
        }
        val seconds = elapsedMs / 1000L
        if (seconds < 60L) {
            return "たった今"
        }
        val minutes = seconds / 60L
        if (minutes < 60L) {
            return "${minutes}分前"
        }
        val hours = minutes / 60L
        return "${hours}時間前"
    }
}
