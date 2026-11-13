package com.example.myasr

import com.example.myasr.context.clipboard.domain.ClipboardUtils

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardUtilsTest {

    @Test
    fun testFormatClipboardPreviewNullOrBlank() {
        assertEquals("📋", ClipboardUtils.formatClipboardPreview(null))
        assertEquals("📋", ClipboardUtils.formatClipboardPreview(""))
        assertEquals("📋", ClipboardUtils.formatClipboardPreview("   \n\t  "))
    }

    @Test
    fun testFormatClipboardPreviewShortText() {
        assertEquals("📋 A", ClipboardUtils.formatClipboardPreview("A"))
        assertEquals("📋 OK", ClipboardUtils.formatClipboardPreview("OK"))
        assertEquals("📋 123", ClipboardUtils.formatClipboardPreview("123"))
        assertEquals("📋 あい", ClipboardUtils.formatClipboardPreview("あい"))
        assertEquals("📋 みちこ", ClipboardUtils.formatClipboardPreview("みちこ")) // 3文字超えるので "みちこ…"
    }

    @Test
    fun testFormatClipboardPreviewLongText() {
        assertEquals("📋 こんに…", ClipboardUtils.formatClipboardPreview("こんにちは世界"))
        assertEquals("📋 htt…", ClipboardUtils.formatClipboardPreview("https://google.com/search?q=myasr"))
        assertEquals("📋 東京都…", ClipboardUtils.formatClipboardPreview("東京都千代田区永田町"))
    }

    @Test
    fun testFormatClipboardPreviewWhitespaceAndNewlines() {
        assertEquals("📋 改行あ…", ClipboardUtils.formatClipboardPreview("\n  改行ありテキスト"))
        assertEquals("📋 ABC…", ClipboardUtils.formatClipboardPreview("   ABC DEF GHI  "))
    }
}
