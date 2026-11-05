package com.example.myasr

object ClipboardUtils {
    /**
     * Formats raw clipboard text for display inside the compact header button.
     * Extracts approximately the first 3 characters with ellipsis, collapsing whitespace and newlines.
     * E.g.
     *  - null or empty -> "📋"
     *  - "こんにちは" -> "📋 こんに…"
     *  - "OK" -> "📋 OK"
     *  - "https://google.com" -> "📋 htt…"
     */
    fun formatClipboardPreview(rawText: String?): String {
        if (rawText.isNullOrBlank()) {
            return "📋"
        }
        val clean = rawText.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) {
            return "📋"
        }
        return if (clean.length > 3) {
            "📋 " + clean.take(3) + "…"
        } else {
            "📋 " + clean
        }
    }
}
