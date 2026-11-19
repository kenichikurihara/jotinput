package com.example.myasr.context.clipboard.presentation

import com.example.myasr.R
import com.example.myasr.context.clipboard.domain.ClipboardUtils
import com.example.myasr.context.clipboard.infrastructure.ClipboardHistoryItem
import com.example.myasr.context.clipboard.infrastructure.ClipboardHistoryRepository
import com.example.myasr.context.clipboard.infrastructure.SensitiveClipMetadata

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Overlay view that displays the clipboard history list directly within the keyboard surface area.
 */
class ClipboardHistoryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onHistoryItemClicked(item: ClipboardHistoryItem)
        fun onSensitiveClipClicked(meta: SensitiveClipMetadata)
        fun onCloseClicked()
    }

    var listener: Listener? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var loadJob: Job? = null

    private val headerLayout: LinearLayout
    private val tvTitle: TextView
    private val btnClearAll: Button
    private val btnClose: Button
    private val scrollView: ScrollView
    private val listContainer: LinearLayout
    private val emptyView: TextView

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.keyboard_background))
        val density = resources.displayMetrics.density

        // Root container: vertical layout
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(rootLayout)

        // Header toolbar (height 36dp)
        headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (36 * density).toInt()
            )
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            setBackgroundColor(ContextCompat.getColor(context, R.color.keyboard_background))
        }
        rootLayout.addView(headerLayout)

        tvTitle = TextView(context).apply {
            text = "📋 クリップボード履歴 (60分保持)"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        headerLayout.addView(tvTitle)

        btnClearAll = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "すべて消去"
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(context, R.color.accent_red))
            val btnLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (30 * density).toInt()
            ).apply {
                marginEnd = (6 * density).toInt()
            }
            layoutParams = btnLp
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            setOnClickListener {
                scope.launch {
                    ClipboardHistoryRepository.getInstance(context).clearAll()
                    refreshHistory()
                }
            }
        }
        headerLayout.addView(btnClearAll)

        btnClose = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "✕ 閉じる"
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(context, R.color.primary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (30 * density).toInt()
            )
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            setOnClickListener {
                listener?.onCloseClicked()
            }
        }
        headerLayout.addView(btnClose)

        // Scroll view with history cards
        scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
            isVerticalScrollBarEnabled = true
        }
        rootLayout.addView(scrollView)

        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (8 * density).toInt())
        }
        scrollView.addView(listContainer)

        emptyView = TextView(context).apply {
            text = "クリップボードの履歴はありません\n（コピーしたテキストは60分間ここに保存されます）"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding((16 * density).toInt(), (32 * density).toInt(), (16 * density).toInt(), (32 * density).toInt())
            visibility = View.GONE
        }
        rootLayout.addView(emptyView)
    }

    fun refreshHistory() {
        loadJob?.cancel()
        loadJob = scope.launch {
            val repo = ClipboardHistoryRepository.getInstance(context)
            val nowMs = System.currentTimeMillis()
            val (items, sensitiveClip, osClipText) = withContext(Dispatchers.Default) {
                repo.syncWithOsClipboard(nowMs)
                val activeItems = repo.getActiveHistory(nowMs)
                val activeSensitive = repo.getActiveSensitiveClipForSheet(nowMs)
                val osText = if (activeSensitive != null) null else repo.getCurrentOsClipText()
                Triple(activeItems, activeSensitive, osText)
            }
            renderItems(items, sensitiveClip, nowMs, osClipText)
        }
    }

    private fun renderItems(
        items: List<ClipboardHistoryItem>,
        sensitiveClip: SensitiveClipMetadata?,
        nowMs: Long,
        osClipText: String?
    ) {
        listContainer.removeAllViews()
        val density = resources.displayMetrics.density

        if (items.isEmpty() && sensitiveClip == null) {
            emptyView.visibility = View.VISIBLE
            scrollView.visibility = View.GONE
            btnClearAll.visibility = View.GONE
            return
        }

        emptyView.visibility = View.GONE
        scrollView.visibility = View.VISIBLE
        btnClearAll.visibility = View.VISIBLE

        if (sensitiveClip != null) {
            val sensitiveCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_candidate_pill)
                val cardLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (6 * density).toInt()
                }
                layoutParams = cardLp
                setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    listener?.onSensitiveClipClicked(sensitiveClip)
                }
            }

            val metaLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val tvBadge = TextView(context).apply {
                text = ClipboardUtils.formatSensitiveBadge()
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(context, R.color.badge_success_text))
                setBackgroundResource(R.drawable.badge_background)
                setPadding((4 * density).toInt(), (1 * density).toInt(), (4 * density).toInt(), (1 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }
            metaLayout.addView(tvBadge)

            val btnDelete = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = "✕"
                textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                layoutParams = LinearLayout.LayoutParams((28 * density).toInt(), (24 * density).toInt())
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    scope.launch {
                        ClipboardHistoryRepository.getInstance(context).dismissSensitiveFromSheet(sensitiveClip.id)
                        refreshHistory()
                    }
                }
            }
            metaLayout.addView(btnDelete)
            sensitiveCard.addView(metaLayout)

            val tvContent = TextView(context).apply {
                text = ClipboardUtils.formatSensitiveHistoryPreview()
                textSize = 13.5f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (2 * density).toInt()
                }
            }
            sensitiveCard.addView(tvContent)
            listContainer.addView(sensitiveCard)
        }

        for (item in items) {
            val isCurrentOsClip = !osClipText.isNullOrEmpty() && item.text == osClipText
            val elapsedMs = nowMs - item.discoveredAtMs

            // Card container for each history item
            val itemCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_candidate_pill)
                val cardLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (6 * density).toInt()
                }
                layoutParams = cardLp
                setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    listener?.onHistoryItemClicked(item)
                }
            }

            // Top sub-header inside card: status badge + delete button
            val metaLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val tvBadge = TextView(context).apply {
                text = ClipboardUtils.formatElapsedTime(elapsedMs, isCurrentOsClip)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(
                    if (isCurrentOsClip) {
                        ContextCompat.getColor(context, R.color.badge_success_text)
                    } else {
                        ContextCompat.getColor(context, R.color.text_secondary)
                    }
                )
                setBackgroundResource(
                    if (isCurrentOsClip) {
                        R.drawable.badge_background
                    } else {
                        0
                    }
                )
                if (isCurrentOsClip) {
                    setPadding((4 * density).toInt(), (1 * density).toInt(), (4 * density).toInt(), (1 * density).toInt())
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }
            metaLayout.addView(tvBadge)

            val btnDelete = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = "✕"
                textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                layoutParams = LinearLayout.LayoutParams((28 * density).toInt(), (24 * density).toInt())
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    scope.launch {
                        ClipboardHistoryRepository.getInstance(context).deleteItem(item.id)
                        refreshHistory()
                    }
                }
            }
            metaLayout.addView(btnDelete)
            itemCard.addView(metaLayout)

            // Content text view
            val tvContent = TextView(context).apply {
                text = ClipboardUtils.formatHistoryItemPreview(item.text, maxChars = 140)
                textSize = 13.5f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                maxLines = 3
                val contentLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (2 * density).toInt()
                }
                layoutParams = contentLp
            }
            itemCard.addView(tvContent)

            listContainer.addView(itemCard)
        }
    }
}
