package com.example.myasr

import com.example.myasr.context.ime.presentation.FlickPopupGeometry
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlickPopupGeometryTest {

    @Test
    fun testAllPetalsStayWithinPopupBounds() {
        val density = 2.625f
        val rowHeight = 148f
        val spec = FlickPopupGeometry.compute(
            keyCenterX = 300f,
            keyCenterY = 180f,
            rowHeight = rowHeight,
            density = density,
            containerWidthPx = 1080
        )

        assertEquals(5, spec.petals.size)
        for (petal in spec.petals) {
            val left = petal.x - spec.petalRadius
            val right = petal.x + spec.petalRadius
            val top = petal.y - spec.petalRadius
            val bottom = petal.y + spec.petalRadius

            assertTrue("Petal ${petal.direction} left=$left must be >= 0", left >= 0f)
            assertTrue("Petal ${petal.direction} top=$top must be >= 0", top >= 0f)
            assertTrue("Petal ${petal.direction} right=$right must be <= ${spec.popupSizePx}", right <= spec.popupSizePx)
            assertTrue("Petal ${petal.direction} bottom=$bottom must be <= ${spec.popupSizePx}", bottom <= spec.popupSizePx)
        }
    }

    @Test
    fun testTopRowProducesNegativePopupYInWindowSpaceWithoutCroppingPetals() {
        val density = 2.625f
        val rowHeight = 148f
        // Top row key center in IME window space (header ~105px + half row ~74px = 179px)
        val spec = FlickPopupGeometry.compute(
            keyCenterX = 540f,
            keyCenterY = 179f,
            rowHeight = rowHeight,
            density = density,
            containerWidthPx = 1080
        )

        // Desired center Y = 179 - 148 * 0.95 = 38.4px, and half popup size > 120px,
        // so popupY is negative in window space (extending above the IME window top)
        assertTrue("Top row popupY (${spec.popupY}) should extend above window top (< 0)", spec.popupY < 0)

        // Yet inside the popup's local canvas, UP petal is completely uncropped
        val upPetal = spec.petals.first { it.direction == JapaneseKanaUtils.FlickDirection.UP }
        assertTrue(upPetal.y - spec.petalRadius >= 12f)
    }

    @Test
    fun testHorizontalClamping() {
        val specLeft = FlickPopupGeometry.compute(
            keyCenterX = 10f,
            keyCenterY = 300f,
            rowHeight = 140f,
            density = 2.5f,
            containerWidthPx = 1080
        )
        assertEquals(0, specLeft.popupX)

        val specRight = FlickPopupGeometry.compute(
            keyCenterX = 1075f,
            keyCenterY = 300f,
            rowHeight = 140f,
            density = 2.5f,
            containerWidthPx = 1080
        )
        assertEquals(1080 - specRight.popupSizePx, specRight.popupX)
    }
}
