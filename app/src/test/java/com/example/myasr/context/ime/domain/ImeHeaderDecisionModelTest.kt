package com.example.myasr.context.ime.domain

import com.example.myasr.context.ime.domain.ImeHeaderDecisionModel.ImeHeaderVisibilityDecision
import com.example.myasr.context.ime.domain.ImeHeaderDecisionModel.ImeHeaderVisibilityInput
import com.example.myasr.context.ime.domain.ImeHeaderDecisionModel.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeHeaderDecisionModelTest {

    @Test
    fun `fully idle shows mic, dictionary, and clipboard icons`() {
        val trace = ImeHeaderDecisionModel.decideHeaderVisibilityWithTrace(
            ImeHeaderVisibilityInput(
                voiceState = VoiceState.IDLE,
                isKeyboardComposing = false,
                isStreamingComposing = false,
                isKeyTouchActive = false,
                isClipboardHistoryShowing = false
            )
        )
        assertEquals("HEADER_R5", trace.selectedRuleId)
        assertEquals(
            ImeHeaderVisibilityDecision(
                showVoiceToggle = true,
                showDictionaryButton = true,
                showClipboardButton = true
            ),
            trace.result
        )
    }

    @Test
    fun `active flick key touch hides mic, dictionary, and clipboard when voice is idle`() {
        val trace = ImeHeaderDecisionModel.decideHeaderVisibilityWithTrace(
            ImeHeaderVisibilityInput(
                voiceState = VoiceState.IDLE,
                isKeyboardComposing = false,
                isStreamingComposing = false,
                isKeyTouchActive = true,
                isClipboardHistoryShowing = false
            )
        )
        assertEquals("HEADER_R4", trace.selectedRuleId)
        assertFalse(trace.result.showVoiceToggle)
        assertFalse(trace.result.showDictionaryButton)
        assertFalse(trace.result.showClipboardButton)
    }

    @Test
    fun `keyboard composing hides mic, dictionary, and clipboard when voice is idle`() {
        val trace = ImeHeaderDecisionModel.decideHeaderVisibilityWithTrace(
            ImeHeaderVisibilityInput(
                voiceState = VoiceState.IDLE,
                isKeyboardComposing = true,
                isStreamingComposing = false,
                isKeyTouchActive = false,
                isClipboardHistoryShowing = false
            )
        )
        assertEquals("HEADER_R4", trace.selectedRuleId)
        assertFalse(trace.result.showVoiceToggle)
        assertFalse(trace.result.showDictionaryButton)
        assertFalse(trace.result.showClipboardButton)
    }

    @Test
    fun `streaming composing hides mic, dictionary, and clipboard when voice is idle`() {
        val trace = ImeHeaderDecisionModel.decideHeaderVisibilityWithTrace(
            ImeHeaderVisibilityInput(
                voiceState = VoiceState.IDLE,
                isKeyboardComposing = false,
                isStreamingComposing = true,
                isKeyTouchActive = false,
                isClipboardHistoryShowing = false
            )
        )
        assertEquals("HEADER_R4", trace.selectedRuleId)
        assertFalse(trace.result.showVoiceToggle)
        assertFalse(trace.result.showDictionaryButton)
        assertFalse(trace.result.showClipboardButton)
    }

    @Test
    fun `recording or decoding with composing keeps voice toggle visible and hides dict and clipboard`() {
        for (state in listOf(VoiceState.RECORDING, VoiceState.DECODING)) {
            val trace = ImeHeaderDecisionModel.decideHeaderVisibilityWithTrace(
                ImeHeaderVisibilityInput(
                    voiceState = state,
                    isKeyboardComposing = true,
                    isStreamingComposing = false,
                    isKeyTouchActive = false,
                    isClipboardHistoryShowing = false
                )
            )
            assertEquals("HEADER_R2", trace.selectedRuleId)
            assertTrue(trace.result.showVoiceToggle)
            assertFalse(trace.result.showDictionaryButton)
            assertFalse(trace.result.showClipboardButton)
        }
    }

    @Test
    fun `recording or decoding without composing keeps all header icons visible`() {
        for (state in listOf(VoiceState.RECORDING, VoiceState.DECODING)) {
            val trace = ImeHeaderDecisionModel.decideHeaderVisibilityWithTrace(
                ImeHeaderVisibilityInput(
                    voiceState = state,
                    isKeyboardComposing = false,
                    isStreamingComposing = false,
                    isKeyTouchActive = false,
                    isClipboardHistoryShowing = false
                )
            )
            assertEquals("HEADER_R3", trace.selectedRuleId)
            assertTrue(trace.result.showVoiceToggle)
            assertTrue(trace.result.showDictionaryButton)
            assertTrue(trace.result.showClipboardButton)
        }
    }

    @Test
    fun `clipboard history open keeps all header icons visible even if composing or key touch is active`() {
        val trace = ImeHeaderDecisionModel.decideHeaderVisibilityWithTrace(
            ImeHeaderVisibilityInput(
                voiceState = VoiceState.IDLE,
                isKeyboardComposing = true,
                isStreamingComposing = false,
                isKeyTouchActive = true,
                isClipboardHistoryShowing = true
            )
        )
        assertEquals("HEADER_R1", trace.selectedRuleId)
        assertTrue(trace.result.showVoiceToggle)
        assertTrue(trace.result.showDictionaryButton)
        assertTrue(trace.result.showClipboardButton)
    }

    @Test
    fun `exhaustive input combinations preserve voice toggle safety invariant`() {
        val bools = listOf(false, true)
        for (voice in VoiceState.values()) {
            for (kb in bools) {
                for (stream in bools) {
                    for (touch in bools) {
                        for (clip in bools) {
                            val input = ImeHeaderVisibilityInput(
                                voiceState = voice,
                                isKeyboardComposing = kb,
                                isStreamingComposing = stream,
                                isKeyTouchActive = touch,
                                isClipboardHistoryShowing = clip
                            )
                            val decision = ImeHeaderDecisionModel.decideHeaderVisibility(input)
                            if (voice != VoiceState.IDLE) {
                                assertTrue(
                                    "Voice toggle must stay visible when voiceState=$voice",
                                    decision.showVoiceToggle
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
