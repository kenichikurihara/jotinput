package com.example.myasr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardHeightManagerTest {

    @Test
    fun testHeightPresets() {
        assertEquals(KeyboardHeightManager.HeightPreset.COMPACT, KeyboardHeightManager.HeightPreset.fromScale(0.80f))
        assertEquals(KeyboardHeightManager.HeightPreset.SHORT, KeyboardHeightManager.HeightPreset.fromScale(0.90f))
        assertEquals(KeyboardHeightManager.HeightPreset.NORMAL, KeyboardHeightManager.HeightPreset.fromScale(1.00f))
        assertEquals(KeyboardHeightManager.HeightPreset.TALL, KeyboardHeightManager.HeightPreset.fromScale(1.10f))
        assertEquals(KeyboardHeightManager.HeightPreset.EXTRA_TALL, KeyboardHeightManager.HeightPreset.fromScale(1.20f))

        // Closeness test
        assertEquals(KeyboardHeightManager.HeightPreset.COMPACT, KeyboardHeightManager.HeightPreset.fromScale(0.82f))
        assertEquals(KeyboardHeightManager.HeightPreset.NORMAL, KeyboardHeightManager.HeightPreset.fromScale(0.98f))
    }

    @Test
    fun testScaleFactors() {
        val presets = KeyboardHeightManager.HeightPreset.entries
        assertEquals(5, presets.size)

        // Ensure ascending order of scales
        for (i in 0 until presets.size - 1) {
            assertTrue(presets[i].scale < presets[i + 1].scale)
        }

        assertTrue(KeyboardHeightManager.HeightPreset.COMPACT.scale < 1.0f)
        assertEquals(1.0f, KeyboardHeightManager.HeightPreset.NORMAL.scale, 0.001f)
        assertTrue(KeyboardHeightManager.HeightPreset.EXTRA_TALL.scale > 1.0f)
    }

    @Test
    fun testSlmContextFreePrefKey() {
        assertEquals("slm_context_free_rerank", KeyboardHeightManager.KEY_SLM_CONTEXT_FREE_RERANK)
    }

    @Test
    fun testSlmWeightAlphaPrefKey() {
        assertEquals("slm_weight_alpha", KeyboardHeightManager.KEY_SLM_WEIGHT_ALPHA)
    }

    @Test
    fun testDebugDisplayPrefKey() {
        assertEquals("debug_display", KeyboardHeightManager.KEY_DEBUG_DISPLAY)
    }

    @Test
    fun testSlmRerankEnabledPrefKey() {
        assertEquals("slm_rerank_enabled", KeyboardHeightManager.KEY_SLM_RERANK_ENABLED)
    }
}
