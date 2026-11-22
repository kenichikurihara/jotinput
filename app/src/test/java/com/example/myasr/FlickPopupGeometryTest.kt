package com.example.myasr

import com.example.myasr.context.ime.presentation.FlickPopupGeometry
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils

import kotlin.math.abs
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

    @Test
    fun testTwelveKeyGridEqualFiveColumnWidthsAcrossTypicalDevices() {
        data class DeviceProfile(
            val name: String,
            val viewWidthDp: Float,
            val heightDp: Float,
            val density: Float,
            val expectedGboardKeyWidthDp: Float?
        )

        // Screen width minus keyboardRoot's 4dp start + 4dp end padding
        val profiles = listOf(
            DeviceProfile("CompactPhone360dp", 352f, 240f, 3.0f, 60.5f),
            DeviceProfile("StandardPhone411dp", 403f, 248f, 2.625f, 70.9f),
            DeviceProfile("LargePhone480dp", 472f, 260f, 3.0f, 84.4f),
            DeviceProfile("Foldable701dp", 693f, 260f, 2.625f, 127.1f),
            DeviceProfile("TabletPortrait800dp", 792f, 280f, 2.0f, 146.8f),
            DeviceProfile("PhoneLandscape914dp", 906f, 180f, 2.625f, 168.9f),
            DeviceProfile("TabletLandscape1600dp", 1592f, 280f, 1.0f, null)
        )

        for (profile in profiles) {
            val widthPx = profile.viewWidthDp * profile.density
            val heightPx = profile.heightDp * profile.density
            val grid = FlickPopupGeometry.computeTwelveKeyGrid(widthPx, heightPx, profile.density)

            // Verify all 5 columns (Col 0 Left Fn, Col 1..3 Kana/Numpad, Col 4 Right Fn) have identical visual key width
            val colVisualWidths = (0..4).map { col ->
                val visualLeft = grid.colLeft(col) + grid.marginX
                val visualRight = grid.colRight(col) - grid.marginX
                visualRight - visualLeft
            }
            for (col in 0..4) {
                assertEquals(
                    "${profile.name} col $col width must equal grid.visualKeyWidth",
                    grid.visualKeyWidth,
                    colVisualWidths[col],
                    0.01f
                )
            }
            assertEquals(
                "${profile.name}: function column width must equal center 12-key column width",
                colVisualWidths[0],
                colVisualWidths[1],
                0.01f
            )
            assertEquals(
                "${profile.name}: left and right function columns must have identical width",
                colVisualWidths[0],
                colVisualWidths[4],
                0.01f
            )

            // Verify empirical proximity to measured Gboard keycap widths on ARCVM
            profile.expectedGboardKeyWidthDp?.let { expectedDp ->
                val actualDp = grid.visualKeyWidth / profile.density
                assertTrue(
                    "${profile.name}: actual key width ${actualDp}dp drifted from Gboard ${expectedDp}dp",
                    abs(actualDp - expectedDp) <= 1.6f
                )
            }
        }
    }

    @Test
    fun testTwelveKeyGridSeamlessTouchHitRectsAndDividerPlacement() {
        val widths = listOf(352f to 3.0f, 403f to 2.625f, 693f to 2.625f, 906f to 2.625f, 1592f to 1.0f)
        for ((viewWidthDp, density) in widths) {
            val widthPx = viewWidthDp * density
            val heightPx = 240f * density
            val grid = FlickPopupGeometry.computeTwelveKeyGrid(widthPx, heightPx, density)

            assertEquals(0f, grid.hitLeft(0), 0.001f)
            assertEquals(widthPx, grid.hitRight(4), 0.001f)
            for (col in 0..3) {
                assertEquals(
                    "Adjacent hitRects between col $col and ${col + 1} must tile without gaps",
                    grid.hitRight(col),
                    grid.hitLeft(col + 1),
                    0.01f
                )
            }

            // Dividers sit cleanly halfway between Col 0/1 visual edges and Col 3/4 visual edges
            val expectedLeftDivider = ((grid.colRight(0) - grid.marginX) + (grid.colLeft(1) + grid.marginX)) / 2f
            val expectedRightDivider = ((grid.colRight(3) - grid.marginX) + (grid.colLeft(4) + grid.marginX)) / 2f
            assertEquals(expectedLeftDivider, grid.leftDividerX, 0.01f)
            assertEquals(expectedRightDivider, grid.rightDividerX, 0.01f)
            assertTrue(grid.pillMarginY > grid.marginY)
        }
    }
}
