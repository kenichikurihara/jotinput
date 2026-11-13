package com.example.myasr.context.ime.presentation

import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Lightweight content view rendered inside [FlickPopupWindow].
 * Explicitly measures itself to [FlickPopupGeometry.Spec.popupSizePx] so it never
 * expands to match_parent.
 * Supports both:
 * 1) Gboard-style circular 5-direction flick guide for 12-key Kana/Modifier/Punctuation
 * 2) Gboard-style single-key callout bubble for QWERTY / Symbols / Numpad keys
 */
class FlickPopupContentView(context: Context) : View(context) {

    private var currentSpec: FlickPopupGeometry.Spec? = null
    private var kanaIndex: Int = -1
    private var currentMode: JapaneseKanaUtils.KeyboardMode = JapaneseKanaUtils.KeyboardMode.KANA
    private var activeDirection: JapaneseKanaUtils.FlickDirection = JapaneseKanaUtils.FlickDirection.CENTER
    private var singleKeyLabel: String? = null

    private val popupBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3C4043")
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 4f, Color.parseColor("#60000000"))
    }

    private val popupActiveBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A73E8")
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 4f, Color.parseColor("#60000000"))
    }

    private val popupCenterTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        textAlign = Paint.Align.CENTER
        textSize = 44f
    }

    private val popupSubTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8EAED")
        textAlign = Paint.Align.CENTER
        textSize = 26f
    }

    private val calloutRect = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        isFocusable = false
    }

    fun bind(
        spec: FlickPopupGeometry.Spec,
        kanaIndex: Int,
        mode: JapaneseKanaUtils.KeyboardMode,
        direction: JapaneseKanaUtils.FlickDirection,
        singleLabel: String? = null
    ) {
        val sizeChanged = currentSpec?.popupSizePx != spec.popupSizePx
        this.currentSpec = spec
        this.kanaIndex = kanaIndex
        this.currentMode = mode
        this.activeDirection = direction
        this.singleKeyLabel = singleLabel
        this.popupCenterTextPaint.textSize = spec.textSizePx * 1.15f
        this.popupSubTextPaint.textSize = spec.textSizePx * 0.72f
        if (sizeChanged) {
            requestLayout()
        }
        invalidate()
    }

    fun setDirection(direction: JapaneseKanaUtils.FlickDirection) {
        if (this.activeDirection != direction) {
            this.activeDirection = direction
            invalidate()
        }
    }

    fun setSingleLabel(label: String) {
        if (this.singleKeyLabel != label) {
            this.singleKeyLabel = label
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = currentSpec?.popupSizePx ?: 0
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val spec = currentSpec ?: return

        val single = singleKeyLabel
        if (single != null) {
            if (single.isEmpty()) return
            val w = (spec.petalRadius * 2.2f).coerceAtMost(spec.popupSizePx * 0.75f)
            val h = (spec.petalRadius * 2.5f).coerceAtMost(spec.popupSizePx * 0.82f)
            calloutRect.set(
                spec.localCenterX - w / 2f,
                spec.localCenterY - h / 2f,
                spec.localCenterX + w / 2f,
                spec.localCenterY + h / 2f
            )
            val corner = w * 0.28f
            canvas.drawRoundRect(calloutRect, corner, corner, popupBgPaint)
            val textOffset = (popupCenterTextPaint.descent() + popupCenterTextPaint.ascent()) / 2f
            canvas.drawText(single, spec.localCenterX, spec.localCenterY - textOffset, popupCenterTextPaint)
            return
        }

        if (kanaIndex < 0) return

        // Gboard-style unified circular flick guide when holding CENTER,
        // or directional petal highlight when flicking
        val unifiedRadius = (spec.distance + spec.petalRadius * 0.68f).coerceAtMost(spec.popupSizePx / 2f - 10f)
        canvas.drawCircle(spec.localCenterX, spec.localCenterY, unifiedRadius, popupBgPaint)

        val centerOffset = (popupCenterTextPaint.descent() + popupCenterTextPaint.ascent()) / 2f
        val subOffset = (popupSubTextPaint.descent() + popupSubTextPaint.ascent()) / 2f

        for (petal in spec.petals) {
            val str = JapaneseKanaUtils.getFlickString(kanaIndex, petal.direction, currentMode)
            if (str.isEmpty()) continue

            val isSelected = (petal.direction == activeDirection)
            if (petal.direction == JapaneseKanaUtils.FlickDirection.CENTER) {
                if (isSelected) {
                    canvas.drawText(str, petal.x, petal.y - centerOffset, popupCenterTextPaint)
                } else {
                    canvas.drawText(str, petal.x, petal.y - subOffset, popupSubTextPaint)
                }
            } else {
                // Position directional labels neatly inside the unified circle
                val dirDist = unifiedRadius * 0.64f
                val dx = when (petal.direction) {
                    JapaneseKanaUtils.FlickDirection.LEFT -> -dirDist
                    JapaneseKanaUtils.FlickDirection.RIGHT -> dirDist
                    else -> 0f
                }
                val dy = when (petal.direction) {
                    JapaneseKanaUtils.FlickDirection.UP -> -dirDist
                    JapaneseKanaUtils.FlickDirection.DOWN -> dirDist
                    else -> 0f
                }
                val tx = spec.localCenterX + dx
                val ty = spec.localCenterY + dy

                if (isSelected) {
                    canvas.drawCircle(tx, ty, spec.petalRadius * 0.85f, popupActiveBgPaint)
                    canvas.drawText(str, tx, ty - subOffset, popupSubTextPaint)
                } else {
                    canvas.drawText(str, tx, ty - subOffset, popupSubTextPaint)
                }
            }
        }
    }
}
