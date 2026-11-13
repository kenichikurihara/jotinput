package com.example.myasr.context.ime.presentation

import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils

import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow

/**
 * Manages the floating [PopupWindow] that renders the 5-petal flick preview or single-key callout
 * above a pressed key, allowing top-row keys ("あ", "か", "さ", "1..0", "q..p") to extend above
 * the IME window without modifying the IME window's height or insets.
 */
class FlickPopupWindow(context: Context) {

    private val TAG = "FlickPopupWindow"

    val contentView = FlickPopupContentView(context)

    private val popupWindow = PopupWindow(contentView).apply {
        isTouchable = false
        isFocusable = false
        isClippingEnabled = false
        setBackgroundDrawable(null)
        animationStyle = 0
    }

    var hasFailed: Boolean = false
        private set

    val isShowing: Boolean
        get() = popupWindow.isShowing

    /**
     * Shows or updates the popup window for the given key.
     * Returns true if the popup window was successfully shown, false if fallback should be used.
     */
    fun show(
        anchor: View,
        keyCenterXInView: Float,
        keyCenterYInView: Float,
        rowHeight: Float,
        kanaIndex: Int,
        mode: JapaneseKanaUtils.KeyboardMode,
        direction: JapaneseKanaUtils.FlickDirection,
        singleLabel: String? = null
    ): Boolean {
        if (hasFailed || !anchor.isAttachedToWindow || anchor.windowToken == null) {
            return false
        }

        val winLoc = IntArray(2)
        val scrLoc = IntArray(2)
        anchor.getLocationInWindow(winLoc)
        anchor.getLocationOnScreen(scrLoc)

        val anchorKeyX = winLoc[0] + keyCenterXInView
        val anchorKeyY = winLoc[1] + keyCenterYInView
        val density = anchor.resources.displayMetrics.density
        val containerWidth = anchor.resources.displayMetrics.widthPixels

        val spec = FlickPopupGeometry.compute(
            keyCenterX = anchorKeyX,
            keyCenterY = anchorKeyY,
            rowHeight = rowHeight,
            density = density,
            containerWidthPx = containerWidth
        )

        contentView.bind(spec, kanaIndex, mode, direction, singleLabel)

        return try {
            popupWindow.width = spec.popupSizePx
            popupWindow.height = spec.popupSizePx
            if (popupWindow.isShowing) {
                popupWindow.update(spec.popupX, spec.popupY, spec.popupSizePx, spec.popupSizePx)
            } else {
                popupWindow.showAtLocation(anchor, Gravity.NO_GRAVITY, spec.popupX, spec.popupY)
            }
            Log.d(
                TAG,
                "show kana=$kanaIndex single=$singleLabel winLoc=(${winLoc[0]},${winLoc[1]}) scrLoc=(${scrLoc[0]},${scrLoc[1]}) " +
                    "popup=(${spec.popupX},${spec.popupY}) size=${spec.popupSizePx}"
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "PopupWindow show failed, falling back to in-view popup: ${e.message}")
            hasFailed = true
            false
        }
    }

    fun updateDirection(direction: JapaneseKanaUtils.FlickDirection) {
        contentView.setDirection(direction)
    }

    fun updateSingleLabel(label: String) {
        contentView.setSingleLabel(label)
    }

    fun dismiss() {
        try {
            if (popupWindow.isShowing) {
                popupWindow.dismiss()
            }
        } catch (e: Exception) {
            Log.w(TAG, "PopupWindow dismiss error: ${e.message}")
        }
    }
}
