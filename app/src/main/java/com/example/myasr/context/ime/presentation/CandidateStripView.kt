package com.example.myasr.context.ime.presentation

import com.example.myasr.R
import com.example.myasr.context.clipboard.domain.ClipboardUtils
import com.example.myasr.context.kanakanji.domain.ConversionCandidate
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.myasr.context.reranking.domain.CandidateRerankJudgement

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

    private var deferredPhase2Action: (() -> Unit)? = null
    private var hasActiveHeroSlot: Boolean = false
    private var isHeroPlaceholderPending: Boolean = false
    private var staleClicksSuppressed: Boolean = false
    private var reservedHeroWidthPx: Int = 0

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> isUserInteracting = true
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                isUserInteracting = false
                flushDeferredPhase2IfNeeded()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> isUserInteracting = true
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                isUserInteracting = false
                flushDeferredPhase2IfNeeded()
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> isUserInteracting = true
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                isUserInteracting = false
                flushDeferredPhase2IfNeeded()
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun flushDeferredPhase2IfNeeded() {
        val pending = deferredPhase2Action ?: return
        deferredPhase2Action = null
        post { pending.invoke() }
    }

    fun deferPhase2UpdateUntilTouchRelease(action: () -> Unit) {
        deferredPhase2Action = action
    }

    /**
     * Disables click handlers on currently displayed stale candidates during the brief
     * Micro-Wait Hold transition (e.g., right after committing a phrase before next-phrase
     * predictions render) to prevent accidental double-commits without flashing empty state.
     */
    fun suppressStaleClicksDuringMicroWait() {
        deferredPhase2Action = null
        staleClicksSuppressed = true
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.isClickable = false
            child.setOnClickListener(null)
        }
    }

    /**
     * Safely resolves any pending "[✨ …]" Hero Slot placeholder when background SLM
     * prediction/reranking times out, fails, or returns empty results.
     */
    fun dismissHeroPlaceholderIfPending(
        fallbackCandidates: List<ConversionCandidate>,
        emptyStatusPrompt: String
    ) {
        deferredPhase2Action = null
        if (!isHeroPlaceholderPending) {
            return
        }
        isHeroPlaceholderPending = false
        if (fallbackCandidates.isNotEmpty()) {
            setCandidatesSinglePass(fallbackCandidates, highlightFirstAsAi = false)
        } else {
            showEmptyStatus(emptyStatusPrompt)
        }
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
        deferredPhase2Action = null
        hasActiveHeroSlot = false
        isHeroPlaceholderPending = false
        staleClicksSuppressed = false
        reservedHeroWidthPx = 0
        currentCandidates = emptyList()
        container.removeAllViews()
        emptyStatusView.text = text
        emptyStatusView.setTextColor(ContextCompat.getColor(context, R.color.candidate_empty_text))
        container.addView(emptyStatusView)
        onEmptyStatusShown?.invoke()
    }

    companion object {
        internal fun filterAndDeduplicateDisplayCandidates(
            candidates: List<ConversionCandidate>
        ): List<ConversionCandidate> {
            val seenSurfaces = HashSet<String>()
            val result = ArrayList<ConversionCandidate>(candidates.size)
            for (cand in candidates) {
                val trimmedSurface = cand.surface.trim { it.isWhitespace() || it == '\u3000' }
                val shouldDisplay = cand.isPrediction ||
                    JapaneseKanaUtils.isCommonParticle(cand.surface) ||
                    cand.reading != cand.surface ||
                    !JapaneseKanaUtils.isAllHiragana(cand.surface)
                if (trimmedSurface.isNotEmpty() && shouldDisplay && seenSurfaces.add(trimmedSurface)) {
                    result.add(
                        if (trimmedSurface == cand.surface) cand else cand.copy(surface = trimmedSurface)
                    )
                }
            }
            return result
        }

        internal fun quantizedMinWidthPx(charCount: Int, density: Float): Int {
            return when (charCount) {
                1 -> (42 * density).toInt()
                2 -> (64 * density).toInt()
                3 -> (86 * density).toInt()
                4 -> (108 * density).toInt()
                else -> 0
            }
        }
    }

    fun computeVisibleCandidateCount(excludeHeroSlot: Boolean = true): Int {
        val viewportRightEdgePx = width + scrollX
        if (width <= 0 || container.childCount == 0) {
            return CandidateRerankJudgement.DEFAULT_VISIBLE_SLOT_FALLBACK
        }
        var visibleCount = 0
        val startIndex = if (excludeHeroSlot && hasActiveHeroSlot) 1 else 0
        for (i in startIndex until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            if (child.left < viewportRightEdgePx) {
                visibleCount++
            } else {
                break
            }
        }
        return visibleCount.coerceAtLeast(1)
    }

    fun setCandidates(candidates: List<ConversionCandidate>) {
        setCandidatesSinglePass(candidates, highlightFirstAsAi = false)
    }

    fun setCandidatesSinglePass(
        candidates: List<ConversionCandidate>,
        highlightFirstAsAi: Boolean = false
    ) {
        val mustRebuildDom = hasActiveHeroSlot || isHeroPlaceholderPending || staleClicksSuppressed
        deferredPhase2Action = null
        hasActiveHeroSlot = false
        isHeroPlaceholderPending = false
        staleClicksSuppressed = false
        reservedHeroWidthPx = 0

        val displayCandidates = filterAndDeduplicateDisplayCandidates(candidates)
        if (displayCandidates.isEmpty()) {
            showEmptyStatus("話すか、フリックで入力")
            return
        }

        // Zero-Jitter optimization: If surfaces are identical to current strip, skip DOM recreation
        val currentSurfaces = filterAndDeduplicateDisplayCandidates(currentCandidates).map { it.surface }
        val newSurfaces = displayCandidates.map { it.surface }
        if (!mustRebuildDom && currentSurfaces == newSurfaces && container.childCount > 0 && !highlightFirstAsAi) {
            currentCandidates = candidates
            return
        }

        currentCandidates = candidates
        container.removeAllViews()

        for ((index, candidate) in displayCandidates.withIndex()) {
            val isAiHighlight = highlightFirstAsAi && index == 0 &&
                candidate.source != "clipboard_recent" &&
                candidate.source != "clipboard_sensitive" &&
                candidate.source != "voice_correction_suggest"
            container.addView(createCandidatePillView(candidate, isAiHighlight = isAiHighlight))
        }
        post { fullScroll(View.FOCUS_LEFT) }
    }

    /**
     * UI-Idea 1 + UI-Idea 8 Phase 1:
     * Reserves a fixed-width AI Hero Slot at index 0 and renders baseCandidates to the right,
     * locking the viewport coordinates for all visible candidates.
     */
    fun renderPhase1WithHeroPlaceholder(
        baseCandidates: List<ConversionCandidate>,
        reservedHeroCharCount: Int = 2
    ): List<ConversionCandidate> {
        deferredPhase2Action = null
        staleClicksSuppressed = false
        val displayCandidates = filterAndDeduplicateDisplayCandidates(baseCandidates)
        if (displayCandidates.isEmpty()) {
            showEmptyStatus("話すか、フリックで入力")
            return emptyList()
        }

        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val density = resources.displayMetrics.density
        val pillHeight = if (isLandscape) (26 * density).toInt() else (30 * density).toInt()
        val textSizeSp = if (isLandscape) 13.5f else 15f
        val marginH = (3 * density).toInt()

        val clampedCharCount = reservedHeroCharCount.coerceIn(2, 4)
        reservedHeroWidthPx = quantizedMinWidthPx(clampedCharCount, density)
        hasActiveHeroSlot = true
        isHeroPlaceholderPending = true
        currentCandidates = displayCandidates

        container.removeAllViews()

        val heroPlaceholder = TextView(context).apply {
            text = "✨ …"
            textSize = textSizeSp
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(context, R.color.primary))
            setBackgroundResource(R.drawable.bg_candidate_pill_hero_placeholder)
            alpha = 0.78f
            isClickable = false
            layoutParams = LinearLayout.LayoutParams(reservedHeroWidthPx, pillHeight).apply {
                setMargins(marginH, 0, marginH, 0)
            }
        }
        container.addView(heroPlaceholder)

        for (candidate in displayCandidates) {
            container.addView(createCandidatePillView(candidate, isAiHighlight = false))
        }
        post { fullScroll(View.FOCUS_LEFT) }
        return displayCandidates
    }

    /**
     * UI-Idea 1 + UI-Idea 8 Phase 2:
     * Non-disruptive DOM update that populates Slot 0 (fixed width), keeps Zone B (visible candidates)
     * 100% untouched in position and View identity, and replaces only Zone C (off-screen tail).
     */
    fun applyPhase2NonDisruptiveUpdate(
        decision: CandidateRerankJudgement.ViewportAwareOrderingDecision
    ) {
        deferredPhase2Action = null
        staleClicksSuppressed = false
        if (decision.strategy != CandidateRerankJudgement.UiRenderStrategy.PHASE2_POPULATE_HERO_AND_RERANK_OFFSCREEN_TAIL) {
            return
        }

        val hero = decision.heroSlotCandidate
        if (hasActiveHeroSlot && container.childCount > 0) {
            if (hero != null) {
                val heroView = container.getChildAt(0) as? TextView
                if (heroView != null) {
                    heroView.text = "✨ ${hero.surface}"
                    heroView.setTextColor(ContextCompat.getColor(context, R.color.primary))
                    heroView.setBackgroundResource(R.drawable.bg_candidate_pill_ai_promoted)
                    heroView.isClickable = true
                    heroView.setOnClickListener {
                        listener?.onCandidateClick(hero)
                    }
                    heroView.alpha = 0.55f
                    heroView.animate().alpha(1.0f).setDuration(150L).start()
                }
            } else {
                // Never leave an unclickable "[✨ …]" placeholder stuck if hero candidate is null
                container.removeViewAt(0)
                hasActiveHeroSlot = false
                reservedHeroWidthPx = 0
            }
            isHeroPlaceholderPending = false
        }

        val zoneBStart = if (hasActiveHeroSlot) 1 else 0
        val zoneBCount = decision.frozenZoneBCandidates.size

        val spotlightIdx = decision.spotlightIndexInViewport
        if (spotlightIdx != null && spotlightIdx in 0 until zoneBCount) {
            val spotlightView = container.getChildAt(zoneBStart + spotlightIdx) as? TextView
            if (spotlightView != null) {
                spotlightView.setBackgroundResource(R.drawable.bg_candidate_pill_ai_promoted)
                spotlightView.setTextColor(ContextCompat.getColor(context, R.color.primary))
            }
        }

        val zoneCStart = zoneBStart + zoneBCount
        if (container.childCount > zoneCStart) {
            container.removeViews(zoneCStart, container.childCount - zoneCStart)
        }
        for (tailCandidate in decision.offScreenZoneCCandidates) {
            container.addView(createCandidatePillView(tailCandidate, isAiHighlight = false))
        }

        currentCandidates = decision.finalCandidates
        // Intentionally do NOT call fullScroll(View.FOCUS_LEFT) so current scrollX is preserved
    }

    private fun createCandidatePillView(
        candidate: ConversionCandidate,
        isAiHighlight: Boolean
    ): TextView {
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val density = resources.displayMetrics.density
        val pillHeight = if (isLandscape) (26 * density).toInt() else (30 * density).toInt()
        val textSizeSp = if (isLandscape) 13.5f else 15f
        val padH = (12 * density).toInt()
        val padV = (3 * density).toInt()
        val marginH = (3 * density).toInt()

        return TextView(context).apply {
            val isClipboardRecent = candidate.source == "clipboard_recent"
            val isClipboardSensitive = candidate.source == "clipboard_sensitive"
            val isVoiceCorrectionSuggest = candidate.source == "voice_correction_suggest"
            val displayText = when {
                isClipboardSensitive -> ClipboardUtils.formatSensitiveCandidateChipPreview()
                isClipboardRecent -> ClipboardUtils.formatCandidateChipPreview(candidate.surface, maxChars = 14)
                isVoiceCorrectionSuggest -> "🔄 ${candidate.surface}"
                isAiHighlight -> "✨ ${candidate.surface}"
                else -> candidate.surface
            }
            text = displayText
            textSize = textSizeSp
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER

            val charCount = candidate.surface.length
            val minW = if (isClipboardRecent || isClipboardSensitive || isVoiceCorrectionSuggest) {
                0
            } else {
                quantizedMinWidthPx(charCount, density)
            }
            minWidth = minW

            if (isAiHighlight) {
                setTextColor(ContextCompat.getColor(context, R.color.primary))
                setBackgroundResource(R.drawable.bg_candidate_pill_ai_promoted)
            } else {
                setTextColor(ContextCompat.getColor(context, R.color.candidate_text))
                setBackgroundResource(R.drawable.bg_candidate_pill)
            }

            setPadding(padH, padV, padH, padV)

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                pillHeight
            ).apply {
                setMargins(marginH, 0, marginH, 0)
            }

            setOnClickListener {
                listener?.onCandidateClick(candidate)
            }
        }
    }
}
