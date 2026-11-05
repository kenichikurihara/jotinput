package com.example.myasr

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

    fun setCandidates(candidates: List<ConversionCandidate>, debugBadge: String? = null) {
        currentCandidates = candidates
        container.removeAllViews()
        // Retain candidates from engine (redundant Hiragana matching composing text is already excluded by engine)
        val displayCandidates = candidates.filter { it.isPrediction || it.reading != it.surface || !JapaneseKanaUtils.isAllHiragana(it.surface) }
        if (displayCandidates.isEmpty()) {
            showEmptyStatus("話すか、フリックで入力")
            return
        }

        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val density = resources.displayMetrics.density
        val pillHeight = if (isLandscape) (26 * density).toInt() else (30 * density).toInt()
        val textSizeSp = if (isLandscape) 13.5f else 15f
        val padH = (12 * density).toInt()
        val padV = (3 * density).toInt()
        val marginH = (3 * density).toInt()

        // Debug badge chip at the front (non-clickable dummy chip)
        if (!debugBadge.isNullOrBlank()) {
            val debugTv = TextView(context).apply {
                text = debugBadge
                textSize = if (isLandscape) 12f else 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.primary))
                setBackgroundResource(R.drawable.bg_candidate_pill)
                setPadding((padH * 0.8f).toInt(), padV, (padH * 0.8f).toInt(), padV)

                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    pillHeight
                ).apply {
                    setMargins(marginH, 0, marginH, 0)
                }
                layoutParams = lp

                isClickable = false
                isFocusable = false
                isEnabled = false
            }
            container.addView(debugTv)
        }

        for (candidate in displayCandidates) {
            val tv = TextView(context).apply {
                text = candidate.surface
                textSize = textSizeSp
                typeface = Typeface.DEFAULT
                gravity = Gravity.CENTER

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
