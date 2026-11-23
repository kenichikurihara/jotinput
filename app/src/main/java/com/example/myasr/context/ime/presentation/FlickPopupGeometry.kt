package com.example.myasr.context.ime.presentation

import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils

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

    data class TwelveKeyGridSpec(
        val widthPx: Float,
        val heightPx: Float,
        val rowHeight: Float,
        val sidePaddingPx: Float,
        val colWidth: Float,
        val marginX: Float,
        val marginY: Float,
        val pillMarginY: Float,
        val leftDividerX: Float,
        val rightDividerX: Float
    ) {
        val visualKeyWidth: Float
            get() = colWidth - 2f * marginX

        fun colLeft(col: Int): Float = sidePaddingPx + col * colWidth
        fun colRight(col: Int): Float = sidePaddingPx + (col + 1) * colWidth
        fun hitLeft(col: Int): Float = if (col == 0) 0f else colLeft(col)
        fun hitRight(col: Int): Float = if (col == 4) widthPx else colRight(col)
    }

    /**
     * Computes Gboard-aligned 5-column x 4-row 12-key geometry (20% equal column weights)
     * for Japanese 12-key Flick and Numpad layouts across phone, foldable, tablet, and landscape sizes.
     */
    fun computeTwelveKeyGrid(
        widthPx: Float,
        heightPx: Float,
        density: Float
    ): TwelveKeyGridSpec {
        val safeDensity = if (density > 0f) density else 1f
        val safeWidthPx = widthPx.coerceAtLeast(0f)
        val safeHeightPx = heightPx.coerceAtLeast(0f)
        val widthDp = safeWidthPx / safeDensity
        val rowHeight = safeHeightPx / 4f
        // Root keyboard container in keyboard_view.xml already provides 4dp start/end padding;
        // on sw600dp+ displays (view width >= 590dp), add 4dp per side so outer body pad matches Gboard (~8dp).
        val sidePaddingPx = if (widthDp >= 590f) 4.0f * safeDensity else 0f
        val innerWidth = (safeWidthPx - sidePaddingPx * 2f).coerceAtLeast(5f)
        val colWidth = innerWidth / 5f

        val marginXDp = when {
            widthDp < 370f -> 4.2f
            widthDp < 830f -> 4.8f
            else -> 5.5f
        }
        val marginX = (marginXDp * safeDensity).coerceAtMost(colWidth * 0.10f)
        val marginY = (4.2f * safeDensity).coerceAtMost(rowHeight * 0.11f)
        val pillMarginY = (marginY + 1.5f * safeDensity).coerceAtMost(rowHeight * 0.14f)
        val leftDividerX = sidePaddingPx + colWidth
        val rightDividerX = safeWidthPx - sidePaddingPx - colWidth

        return TwelveKeyGridSpec(
            widthPx = safeWidthPx,
            heightPx = safeHeightPx,
            rowHeight = rowHeight,
            sidePaddingPx = sidePaddingPx,
            colWidth = colWidth,
            marginX = marginX,
            marginY = marginY,
            pillMarginY = pillMarginY,
            leftDividerX = leftDividerX,
            rightDividerX = rightDividerX
        )
    }

    data class TwelveKeyTypographySpec(
        val mainTextSizePx: Float,
        val fnTextSizePx: Float,
        val actionTextSizePx: Float,
        val centerSubTextSizePx: Float,
        val popupTextSizePx: Float,
        val iconSizePx: Float,
        val iconStrokeWidthPx: Float
    )

    /**
     * Computes density-scaled typography and Material vector icon dimensions for 12-key Flick
     * and Numpad layouts so keytop glyphs and icons scale consistently with Gboard across DPIs.
     */
    fun computeTwelveKeyTypography(
        grid: TwelveKeyGridSpec,
        density: Float
    ): TwelveKeyTypographySpec {
        val safeDensity = if (density > 0f) density else 1f
        val keyHeight = (grid.rowHeight - 2f * grid.marginY).coerceAtLeast(1f)
        val iconSizePx = minOf(keyHeight * 0.50f, grid.visualKeyWidth * 0.56f)
            .coerceIn(17f * safeDensity, 27f * safeDensity)
        val iconStrokeWidthPx = (iconSizePx * 0.095f)
            .coerceIn(1.7f * safeDensity, 2.6f * safeDensity)

        return TwelveKeyTypographySpec(
            mainTextSizePx = (keyHeight * 0.56f).coerceIn(16f * safeDensity, 31f * safeDensity),
            fnTextSizePx = (keyHeight * 0.36f).coerceIn(12f * safeDensity, 20f * safeDensity),
            actionTextSizePx = (keyHeight * 0.36f).coerceIn(13f * safeDensity, 20f * safeDensity),
            centerSubTextSizePx = (keyHeight * 0.25f).coerceIn(10f * safeDensity, 14.5f * safeDensity),
            popupTextSizePx = (keyHeight * 0.44f).coerceIn(16f * safeDensity, 26f * safeDensity),
            iconSizePx = iconSizePx,
            iconStrokeWidthPx = iconStrokeWidthPx
        )
    }

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
