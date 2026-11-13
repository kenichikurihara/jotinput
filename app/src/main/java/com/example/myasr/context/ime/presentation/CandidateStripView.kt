package com.example.myasr.context.ime.presentation

import com.example.myasr.R
import com.example.myasr.context.clipboard.domain.ClipboardUtils
import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class CandidateStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    interface OnCandidateClickListener {
        fun onCandidateClick(candidate: ConversionCandidate)
    }

    var listener: OnCandidateClickListener? = null

    var isUserInteracting: Boolean = false
        private set

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> isUserInteracting = true
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> isUserInteracting = false
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> isUserInteracting = true
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> isUserInteracting = false
        }
        return super.onTouchEvent(ev)
    }

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        setPadding(6, 2, 6, 2)
    }

    private val emptyStatusView = TextView(context).apply {
        text = "話すか、フリックで入力"
        setTextColor(ContextCompat.getColor(context, R.color.candidate_empty_text))
        textSize = 13f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(16, 0, 16, 0)
    }

    private var currentCandidates: List<ConversionCandidate> = emptyList()

    fun getCandidates(): List<ConversionCandidate> = currentCandidates

    init {
        isHorizontalScrollBarEnabled = false
        addView(container)
        showEmptyStatus("話すか、フリックで入力")
    }

    var onEmptyStatusShown: (() -> Unit)? = null

    fun showEmptyStatus(text: String) {
        currentCandidates = emptyList()
        container.removeAllViews()
        emptyStatusView.text = text
        emptyStatusView.setTextColor(ContextCompat.getColor(context, R.color.candidate_empty_text))
        container.addView(emptyStatusView)
        onEmptyStatusShown?.invoke()
    }

    fun setCandidates(candidates: List<ConversionCandidate>) {
        // Retain candidates from engine (redundant Hiragana matching composing text is already excluded by engine)
        val displayCandidates = candidates.filter { it.isPrediction || it.reading != it.surface || !JapaneseKanaUtils.isAllHiragana(it.surface) }
        if (displayCandidates.isEmpty()) {
            showEmptyStatus("話すか、フリックで入力")
            return
        }

        // Zero-Jitter optimization: If surfaces are identical to current strip, skip DOM recreation
        val currentSurfaces = currentCandidates.filter { it.isPrediction || it.reading != it.surface || !JapaneseKanaUtils.isAllHiragana(it.surface) }.map { it.surface }
        val newSurfaces = displayCandidates.map { it.surface }
        if (currentSurfaces == newSurfaces && container.childCount > 0) {
            currentCandidates = candidates
            return
        }

        currentCandidates = candidates
        container.removeAllViews()

        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val density = resources.displayMetrics.density
        val pillHeight = if (isLandscape) (26 * density).toInt() else (30 * density).toInt()
        val textSizeSp = if (isLandscape) 13.5f else 15f
        val padH = (12 * density).toInt()
        val padV = (3 * density).toInt()
        val marginH = (3 * density).toInt()

        for (candidate in displayCandidates) {
            val tv = TextView(context).apply {
                val isClipboardRecent = candidate.source == "clipboard_recent"
                val displayText = if (isClipboardRecent) {
                    ClipboardUtils.formatCandidateChipPreview(candidate.surface, maxChars = 14)
                } else {
                    candidate.surface
                }
                text = displayText
                textSize = textSizeSp
                typeface = Typeface.DEFAULT
                gravity = Gravity.CENTER

                // Quantized minimum width by character count: eliminates cumulative layout shifts (CLS)
                // when homophones of the same character count exchange positions.
                val charCount = candidate.surface.length
                val minW = if (isClipboardRecent) {
                    0
                } else {
                    when (charCount) {
                        1 -> (42 * density).toInt()
                        2 -> (64 * density).toInt()
                        3 -> (86 * density).toInt()
                        4 -> (108 * density).toInt()
                        else -> 0
                    }
                }
                minWidth = minW

                setTextColor(ContextCompat.getColor(context, R.color.candidate_text))
                setBackgroundResource(R.drawable.bg_candidate_pill)

                setPadding(padH, padV, padH, padV)

                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    pillHeight
                ).apply {
                    setMargins(marginH, 0, marginH, 0)
                }
                layoutParams = lp

                setOnClickListener {
                    listener?.onCandidateClick(candidate)
                }
            }
            container.addView(tv)
        }
        // Scroll to start
        post { fullScroll(View.FOCUS_LEFT) }
    }
}
