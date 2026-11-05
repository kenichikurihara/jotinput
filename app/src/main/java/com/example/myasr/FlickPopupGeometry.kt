package com.example.myasr

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Pure-Kotlin geometry calculator for the 5-petal Japanese flick popup preview.
 * Free of Android framework dependencies so it can be unit-tested directly on the JVM.
 */
object FlickPopupGeometry {

    data class PetalPoint(
        val direction: JapaneseKanaUtils.FlickDirection,
        val x: Float,
        val y: Float
    )

    data class Spec(
        val popupSizePx: Int,
        val petalRadius: Float,
        val distance: Float,
        val textSizePx: Float,
        val localCenterX: Float,
        val localCenterY: Float,
        val popupX: Int,
        val popupY: Int,
        val petals: List<PetalPoint>
    )

    /**
     * Computes popup dimensions, local petal coordinates, and popup window (x, y) placement.
     *
     * @param keyCenterX X coordinate of the pressed key's center in anchor space
     * @param keyCenterY Y coordinate of the pressed key's center in anchor space
     * @param rowHeight Height of the key row in pixels
     * @param density Display density (resources.displayMetrics.density)
     * @param containerWidthPx Optional width of the screen/window to horizontally clamp popupX
     */
    fun compute(
        keyCenterX: Float,
        keyCenterY: Float,
        rowHeight: Float,
        density: Float,
        containerWidthPx: Int = 0
    ): Spec {
        val petalRadius = (rowHeight * 0.34f).coerceIn(16f * density, 26f * density)
        val distance = (rowHeight * 0.52f).coerceIn(24f * density, 38f * density)
        val textSizePx = (rowHeight * 0.35f).coerceIn(20f, 38f)

        // Padding for shadow layer (blur 8px + dy 4px)
        val shadowPad = (6f * density).coerceAtLeast(12f)
        val halfExtent = distance + petalRadius + shadowPad
        val popupSizePx = ceil(halfExtent * 2f).toInt()

        val localCx = popupSizePx / 2f
        val localCy = popupSizePx / 2f

        val desiredCenterX = keyCenterX
        val desiredCenterY = keyCenterY - (rowHeight * 0.95f)

        val rawPopupX = (desiredCenterX - localCx).roundToInt()
        val popupY = (desiredCenterY - localCy).roundToInt()

        val popupX = if (containerWidthPx > popupSizePx) {
            rawPopupX.coerceIn(0, containerWidthPx - popupSizePx)
        } else {
            rawPopupX
        }

        val petals = listOf(
            PetalPoint(JapaneseKanaUtils.FlickDirection.CENTER, localCx, localCy),
            PetalPoint(JapaneseKanaUtils.FlickDirection.LEFT, localCx - distance, localCy),
            PetalPoint(JapaneseKanaUtils.FlickDirection.UP, localCx, localCy - distance),
            PetalPoint(JapaneseKanaUtils.FlickDirection.RIGHT, localCx + distance, localCy),
            PetalPoint(JapaneseKanaUtils.FlickDirection.DOWN, localCx, localCy + distance)
        )

        return Spec(
            popupSizePx = popupSizePx,
            petalRadius = petalRadius,
            distance = distance,
            textSizePx = textSizePx,
            localCenterX = localCx,
            localCenterY = localCy,
            popupX = popupX,
            popupY = popupY,
            petals = petals
        )
    }
}
