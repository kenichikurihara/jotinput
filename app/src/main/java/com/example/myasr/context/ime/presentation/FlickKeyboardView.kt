package com.example.myasr.context.ime.presentation

import com.example.myasr.context.english.infrastructure.FutoSwipeEngine
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.sqrt

class FlickKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnKeyboardActionListener {
        fun onTextChar(text: String)
        fun onModifierPressed(direction: JapaneseKanaUtils.FlickDirection = JapaneseKanaUtils.FlickDirection.CENTER)
        fun onDeletePressed(count: Int = 1)
        fun onSpacePressed()
        fun onEnterPressed()
        fun onGlobePressed()
        fun onDictPressed()
        fun onCursorLeft() {}
        fun onCursorRight() {}
        fun onUndoPressed() {}
        fun onSwipeInput(trajectory: List<FutoSwipeEngine.SwipePoint>) {}
        fun onKeyTouchDown() {}
        fun onKeyTouchUpOrCancel() {}
        fun onModeChanged(mode: JapaneseKanaUtils.KeyboardMode)
    }

    var listener: OnKeyboardActionListener? = null

    var currentMode = JapaneseKanaUtils.KeyboardMode.KANA
        private set

    var showNumberRowInQwerty: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                layoutKeys(width.toFloat(), height.toFloat())
                invalidate()
            }
        }

    var qwertyCursorControlMode: KeyboardHeightManager.QwertyCursorControlMode =
        KeyboardHeightManager.QwertyCursorControlMode.ARROW_KEYS
        set(value) {
            if (field != value) {
                field = value
                layoutKeys(width.toFloat(), height.toFloat())
                invalidate()
            }
        }

    enum class ShiftState {
        OFF,
        SHIFT_ONCE,
        CAPS_LOCK
    }

    var shiftState: ShiftState = ShiftState.OFF
        private set

    private var isComposingState: Boolean = false
    private var canUndoState: Boolean = false
    private var hasVoiceComposingState: Boolean = false
    private var isCharKeyTouchNotified: Boolean = false
    private var previousAlphaOrKanaMode = JapaneseKanaUtils.KeyboardMode.KANA
    private var lastShiftTapTimeMs: Long = 0L

    private enum class KeyType {
        KANA_FLICK,
        MODIFIER_FLICK,
        PUNCTUATION_FLICK,
        QWERTY_CHAR,
        MODE_KANA_ALPHA,
        MODE_SYMBOLS,
        MODE_NUMPAD,
        MODE_BACK_KANA,
        SHIFT,
        UNDO,
        CURSOR_LEFT,
        CURSOR_RIGHT,
        GLOBE,
        DELETE,
        SPACE,
        DICT,
        ENTER
    }

    private data class Key(
        val type: KeyType,
        val kanaIndex: Int = -1,
        val label: String,
        val subLabel: String = "",
        val rect: RectF = RectF(),
        val hitRect: RectF = RectF(),
        val isPill: Boolean = false,
        val isAccent: Boolean = false,
        val isFunctionKey: Boolean = false,
        var isPressed: Boolean = false
    )

    @Volatile
    private var keys: List<Key> = emptyList()
    private var activeKey: Key? = null
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var activeDirection = JapaneseKanaUtils.FlickDirection.CENTER
    private var longPressTriggered = false
    private val flickPopupWindow = FlickPopupWindow(context)
    private var usingWindowPopup = false

    private val handler = Handler(Looper.getMainLooper())
    private var deleteRepeatStartTimeMs = 0L
    private var deleteRepeatCount = 0
    private var lastRepeatHapticTimeMs = 0L
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val key = activeKey ?: return
            when (key.type) {
                KeyType.DELETE -> {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (deleteRepeatStartTimeMs == 0L) {
                        deleteRepeatStartTimeMs = now
                    }
                    val step = DeleteRepeatAccelerator.stepForElapsedMs(now - deleteRepeatStartTimeMs)
                    deleteRepeatCount++
                    if (now - lastRepeatHapticTimeMs >= DeleteRepeatAccelerator.MIN_HAPTIC_INTERVAL_MS) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        lastRepeatHapticTimeMs = now
                    }
                    listener?.onDeletePressed(step.deleteCount)
                    handler.postDelayed(this, step.delayMs)
                }
                KeyType.CURSOR_LEFT -> {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    listener?.onCursorLeft()
                    handler.postDelayed(this, 65L)
                }
                KeyType.CURSOR_RIGHT -> {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    listener?.onCursorRight()
                    handler.postDelayed(this, 65L)
                }
                else -> {}
            }
        }
    }

    private val qwertyLongPressRunnable = Runnable {
        val key = activeKey ?: return@Runnable
        if (key.type == KeyType.QWERTY_CHAR && key.subLabel.isNotEmpty()) {
            longPressTriggered = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (usingWindowPopup) {
                flickPopupWindow.updateSingleLabel(key.subLabel)
            } else {
                invalidate()
            }
        }
    }

    // Paints
    private val keyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
    }
    private val keyFnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E1E5EA")
        style = Paint.Style.FILL
    }
    private val keyPillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D3D8E0")
        style = Paint.Style.FILL
    }
    private val keyActionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A73E8")
        style = Paint.Style.FILL
    }
    private val keyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C4CDD5")
        style = Paint.Style.FILL
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD0D6")
        strokeWidth = 2.5f
    }
    private val keyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#202124")
        textAlign = Paint.Align.CENTER
        textSize = 46f
    }
    private val keyFnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3C4043")
        textAlign = Paint.Align.CENTER
        textSize = 36f
    }
    private val keyMutedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA0A6")
        textAlign = Paint.Align.CENTER
        textSize = 36f
    }
    private val keyActionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        textAlign = Paint.Align.CENTER
        textSize = 42f
    }
    private val keySubTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5F6368")
        textAlign = Paint.Align.RIGHT
        textSize = 22f
    }
    private val keyCenterSubTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5F6368")
        textAlign = Paint.Align.CENTER
        textSize = 22f
    }
    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val iconPath = Path()
    private var currentIconSizePx: Float = 24f * resources.displayMetrics.density
    private var currentIconStrokeWidthPx: Float = 2.2f * resources.displayMetrics.density

    // Fallback In-Canvas Popup Preview Paints
    private val popupBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3C4043")
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 4f, Color.parseColor("#60000000"))
    }
    private val popupActiveBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A73E8")
        style = Paint.Style.FILL
    }
    private val popupTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        textAlign = Paint.Align.CENTER
        textSize = 38f
    }

    // Swipe trajectory rendering & state
    private val swipePathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#801A73E8") // Semi-transparent blue
        style = Paint.Style.STROKE
        strokeWidth = 10f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val swipeTrajectory = mutableListOf<FutoSwipeEngine.SwipePoint>()
    private var isSwiping = false
    private val swipeThresholdPx = 18f * resources.displayMetrics.density
    private var qwertyAlphaTop: Float = 0f
    private var qwertyAlphaBottom: Float = 0f

    private val flickThresholdPx = 26f * resources.displayMetrics.density
    private val spacebarSwipeTracker = SpacebarCursorSwipeTracker(
        activationThresholdPx = 14f * resources.displayMetrics.density,
        stepThresholdPx = 12f * resources.displayMetrics.density
    )

    private fun isSpacebarSwipeEnabledForCurrentMode(): Boolean {
        return qwertyCursorControlMode == KeyboardHeightManager.QwertyCursorControlMode.SPACEBAR_SWIPE &&
            (currentMode == JapaneseKanaUtils.KeyboardMode.QWERTY ||
                currentMode == JapaneseKanaUtils.KeyboardMode.QWERTY_SYMBOLS)
    }

    private fun isAlphabetSwipeKey(key: Key): Boolean {
        return key.type == KeyType.QWERTY_CHAR &&
            key.label.length == 1 &&
            key.label[0].lowercaseChar() in 'a'..'z'
    }

    private fun normalizeSwipeY(y: Float): Float {
        val alphaHeight = (qwertyAlphaBottom - qwertyAlphaTop).takeIf { it > 1f } ?: height.toFloat().coerceAtLeast(1f)
        return ((y - qwertyAlphaTop) / alphaHeight).coerceIn(0f, 1f)
    }

    private fun denormalizeSwipeY(normY: Float): Float {
        val alphaHeight = (qwertyAlphaBottom - qwertyAlphaTop).takeIf { it > 1f } ?: height.toFloat().coerceAtLeast(1f)
        return qwertyAlphaTop + normY * alphaHeight
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        showNumberRowInQwerty = KeyboardHeightManager.isQwertyNumberRowEnabled(context)
        qwertyCursorControlMode = KeyboardHeightManager.getQwertyCursorControlMode(context)
        updateThemeColors()
    }

    fun updateThemeColors() {
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (isNight) {
            // Gboard Dark Palette
            keyBgPaint.color = Color.parseColor("#2D2F31")
            keyFnBgPaint.color = Color.parseColor("#1E1F22")
            keyPillBgPaint.color = Color.parseColor("#3C4043")
            keyActionBgPaint.color = Color.parseColor("#8AB4F8")
            keyPressedPaint.color = Color.parseColor("#484B4F")
            dividerPaint.color = Color.parseColor("#3C4043")
            keyTextPaint.color = Color.parseColor("#E8EAED")
            keyFnTextPaint.color = Color.parseColor("#E8EAED")
            keyMutedTextPaint.color = Color.parseColor("#6E7378")
            keyActionTextPaint.color = Color.parseColor("#041E49")
            keySubTextPaint.color = Color.parseColor("#9AA0A6")
            keyCenterSubTextPaint.color = Color.parseColor("#9AA0A6")
            popupBgPaint.color = Color.parseColor("#2D2F31")
            popupActiveBgPaint.color = Color.parseColor("#8AB4F8")
            popupTextPaint.color = Color.parseColor("#FFFFFF")
            swipePathPaint.color = Color.parseColor("#808AB4F8") // Semi-transparent blue for Dark mode
        } else {
            // Gboard Light Palette
            keyBgPaint.color = Color.parseColor("#FFFFFF")
            keyFnBgPaint.color = Color.parseColor("#E1E5EA")
            keyPillBgPaint.color = Color.parseColor("#D3D8E0")
            keyActionBgPaint.color = Color.parseColor("#1A73E8")
            keyPressedPaint.color = Color.parseColor("#C4CDD5")
            dividerPaint.color = Color.parseColor("#CBD0D6")
            keyTextPaint.color = Color.parseColor("#202124")
            keyFnTextPaint.color = Color.parseColor("#3C4043")
            keyMutedTextPaint.color = Color.parseColor("#787E84")
            keyActionTextPaint.color = Color.parseColor("#FFFFFF")
            keySubTextPaint.color = Color.parseColor("#5F6368")
            keyCenterSubTextPaint.color = Color.parseColor("#5F6368")
            popupBgPaint.color = Color.parseColor("#3C4043")
            popupActiveBgPaint.color = Color.parseColor("#1A73E8")
            popupTextPaint.color = Color.parseColor("#FFFFFF")
            swipePathPaint.color = Color.parseColor("#801A73E8") // Semi-transparent blue for Light mode
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateThemeColors()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateThemeColors()
    }

    fun syncPreferences() {
        updateThemeColors()
        val numRow = KeyboardHeightManager.isQwertyNumberRowEnabled(context)
        val cursorMode = KeyboardHeightManager.getQwertyCursorControlMode(context)
        val savedMode = KeyboardHeightManager.getLastKeyboardMode(context)
        var changed = false
        if (showNumberRowInQwerty != numRow) {
            showNumberRowInQwerty = numRow
            changed = true
        }
        if (qwertyCursorControlMode != cursorMode) {
            qwertyCursorControlMode = cursorMode
            changed = true
        }
        if (currentMode != savedMode && currentMode != JapaneseKanaUtils.KeyboardMode.QWERTY_SYMBOLS && currentMode != JapaneseKanaUtils.KeyboardMode.NUMBER) {
            currentMode = savedMode
            changed = true
        }
        if (changed) {
            layoutKeys(width.toFloat(), height.toFloat())
            invalidate()
        }
    }

    fun setMode(mode: JapaneseKanaUtils.KeyboardMode) {
        if (currentMode != mode) {
            dismissPopup()
            currentMode = mode
            if (mode == JapaneseKanaUtils.KeyboardMode.KANA || mode == JapaneseKanaUtils.KeyboardMode.QWERTY) {
                previousAlphaOrKanaMode = mode
                KeyboardHeightManager.setLastKeyboardMode(context, mode)
            }
            listener?.onModeChanged(currentMode)
            layoutKeys(width.toFloat(), height.toFloat())
            invalidate()
        }
    }

    fun setComposingState(isComposing: Boolean, canUndo: Boolean, hasVoiceComposing: Boolean = false) {
        if (this.isComposingState != isComposing || this.canUndoState != canUndo || this.hasVoiceComposingState != hasVoiceComposing) {
            this.isComposingState = isComposing
            this.canUndoState = canUndo
            this.hasVoiceComposingState = hasVoiceComposing
            layoutKeys(width.toFloat(), height.toFloat())
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys(w.toFloat(), h.toFloat())
    }

    private fun layoutKeys(width: Float, height: Float) {
        if (width <= 0 || height <= 0) {
            keys = emptyList()
            return
        }

        val targetList = ArrayList<Key>()
        when (currentMode) {
            JapaneseKanaUtils.KeyboardMode.KANA -> targetList.layoutGboardFlickKeys(width, height)
            JapaneseKanaUtils.KeyboardMode.QWERTY -> targetList.layoutGboardQwertyKeys(width, height)
            JapaneseKanaUtils.KeyboardMode.QWERTY_SYMBOLS -> targetList.layoutGboardSymbolsKeys(width, height)
            JapaneseKanaUtils.KeyboardMode.NUMBER -> targetList.layoutGboardNumpadKeys(width, height)
        }
        keys = targetList
    }

    private fun MutableList<Key>.addKey(
        type: KeyType,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        marginX: Float,
        marginY: Float,
        label: String,
        subLabel: String = "",
        kanaIndex: Int = -1,
        isPill: Boolean = false,
        isAccent: Boolean = false,
        isFunctionKey: Boolean = false,
        hitLeft: Float = left,
        hitRight: Float = right
    ) {
        val visualRect = RectF(left + marginX, top + marginY, right - marginX, bottom - marginY)
        val hitRect = RectF(hitLeft, top, hitRight, bottom)
        add(
            Key(
                type = type,
                kanaIndex = kanaIndex,
                label = label,
                subLabel = subLabel,
                rect = visualRect,
                hitRect = hitRect,
                isPill = isPill,
                isAccent = isAccent,
                isFunctionKey = isFunctionKey
            )
        )
    }

    private fun MutableList<Key>.addTwelveGridKey(
        grid: FlickPopupGeometry.TwelveKeyGridSpec,
        col: Int,
        row: Int,
        type: KeyType,
        label: String,
        kanaIndex: Int = -1,
        isPill: Boolean = false,
        isAccent: Boolean = false,
        isFunctionKey: Boolean = false
    ) {
        val top = row * grid.rowHeight
        addKey(
            type = type,
            left = grid.colLeft(col),
            top = top,
            right = grid.colRight(col),
            bottom = top + grid.rowHeight,
            marginX = grid.marginX,
            marginY = if (isPill) grid.pillMarginY else grid.marginY,
            label = label,
            kanaIndex = kanaIndex,
            isPill = isPill,
            isAccent = isAccent,
            isFunctionKey = isFunctionKey,
            hitLeft = grid.hitLeft(col),
            hitRight = grid.hitRight(col)
        )
    }

    private fun applyTwelveKeyTypography(
        grid: FlickPopupGeometry.TwelveKeyGridSpec,
        density: Float
    ) {
        val typo = FlickPopupGeometry.computeTwelveKeyTypography(grid, density)
        keyTextPaint.textSize = typo.mainTextSizePx
        keyFnTextPaint.textSize = typo.fnTextSizePx
        keyMutedTextPaint.textSize = typo.fnTextSizePx
        keyActionTextPaint.textSize = typo.actionTextSizePx
        keyCenterSubTextPaint.textSize = typo.centerSubTextSizePx
        popupTextPaint.textSize = typo.popupTextSizePx
        currentIconSizePx = typo.iconSizePx
        currentIconStrokeWidthPx = typo.iconStrokeWidthPx
    }

    private fun applyQwertyTypography(
        rowHeight: Float,
        marginY: Float,
        density: Float
    ) {
        val safeDensity = if (density > 0f) density else 1f
        val keyHeight = (rowHeight - 2f * marginY).coerceAtLeast(1f)
        keyTextPaint.textSize = (keyHeight * 0.52f).coerceIn(15f * safeDensity, 24f * safeDensity)
        keyFnTextPaint.textSize = (keyHeight * 0.36f).coerceIn(11f * safeDensity, 17f * safeDensity)
        keyMutedTextPaint.textSize = keyFnTextPaint.textSize
        keyActionTextPaint.textSize = (keyHeight * 0.38f).coerceIn(12f * safeDensity, 18f * safeDensity)
        keySubTextPaint.textSize = (keyHeight * 0.22f).coerceIn(8.5f * safeDensity, 12f * safeDensity)
        keyCenterSubTextPaint.textSize = keySubTextPaint.textSize
        popupTextPaint.textSize = (keyHeight * 0.48f).coerceIn(16f * safeDensity, 24f * safeDensity)
        currentIconSizePx = (keyHeight * 0.50f).coerceIn(16f * safeDensity, 24f * safeDensity)
        currentIconStrokeWidthPx = (currentIconSizePx * 0.095f).coerceIn(1.6f * safeDensity, 2.4f * safeDensity)
    }

    /**
     * Gboard-inspired 12-key Flick-Only Japanese Layout (5 equal columns, 20.0% each):
     * - Left Function Column (20%): Undo (↶), Cursor Left (◀), ?123, Mode Toggle Pill (あa)
     * - Center 3x4 Grid (60%, 20% per col): Clean primary labels only (あ か さ / た な は / ま や ら / ゛゜大⇔小 わ 、。？！…)
     * - Right Function Column (20%): Delete (⌫), Cursor Right (▶), Space (␣ / 変換), Enter Pill (↵ / 確定)
     */
    private fun MutableList<Key>.layoutGboardFlickKeys(width: Float, height: Float) {
        val density = resources.displayMetrics.density
        val grid = FlickPopupGeometry.computeTwelveKeyGrid(width, height, density)
        applyTwelveKeyTypography(grid, density)

        // Col 0: Left Function Column (all keys share marginX so vertical column edges align cleanly)
        addTwelveGridKey(grid, col = 0, row = 0, KeyType.UNDO, "↶", isFunctionKey = true)
        addTwelveGridKey(grid, col = 0, row = 1, KeyType.CURSOR_LEFT, "◀", isFunctionKey = true)
        addTwelveGridKey(grid, col = 0, row = 2, KeyType.MODE_NUMPAD, "?123", isFunctionKey = true)
        addTwelveGridKey(grid, col = 0, row = 3, KeyType.MODE_KANA_ALPHA, "あa", isPill = true, isFunctionKey = true)

        // Col 1..3: 12-key Flick-Only Center Grid (No sub-labels on Kana keys!)
        val mainLabels = arrayOf(
            "あ", "か", "さ",
            "た", "な", "は",
            "ま", "や", "ら",
            "゛゜", "わ", "、。?!"
        )
        for (row in 0..3) {
            for (col in 0..2) {
                val index = row * 3 + col
                val keyType = when (index) {
                    9 -> KeyType.MODIFIER_FLICK
                    11 -> KeyType.PUNCTUATION_FLICK
                    else -> KeyType.KANA_FLICK
                }
                addTwelveGridKey(grid, col = col + 1, row = row, type = keyType, label = mainLabels[index], kanaIndex = index)
            }
        }

        // Col 4: Right Function Column (all keys share marginX so vertical column edges align cleanly)
        val enterLabel = if (isComposingState || hasVoiceComposingState) "確定" else "↵"
        addTwelveGridKey(grid, col = 4, row = 0, KeyType.DELETE, "⌫", isFunctionKey = true)
        addTwelveGridKey(grid, col = 4, row = 1, KeyType.CURSOR_RIGHT, "▶", isFunctionKey = true)
        addTwelveGridKey(grid, col = 4, row = 2, KeyType.SPACE, "␣", isFunctionKey = true)
        addTwelveGridKey(grid, col = 4, row = 3, KeyType.ENTER, enterLabel, isPill = true, isAccent = true, isFunctionKey = true)
    }

    /**
     * Gboard-inspired English QWERTY Layout:
     * - Optional Row 0 (1..0) when showNumberRowInQwerty == true
     * - Row A: q w e r t y u i o p (with top-right symbols or numbers)
     * - Row B: a s d f g h j k l (5% left/right indent, top-right symbols)
     * - Row C: Shift (15%), z x c v b n m (10% each), Delete (15%)
     * - Row D: あa pill (13%), ?123 (11%), ',' (9%), Space pill (34%), '.' (9%), ◀ (5.5%), ▶ (5.5%), Enter pill (13%)
     */
    private fun MutableList<Key>.layoutGboardQwertyKeys(width: Float, height: Float) {
        val density = resources.displayMetrics.density
        val totalRows = if (showNumberRowInQwerty) 5 else 4
        val rowHeight = height / totalRows.toFloat()
        val marginX = 2.2f * density
        val marginY = 3.0f * density

        applyQwertyTypography(rowHeight, marginY, density)

        var currentRow = 0
        val col10Width = width / 10f
        val isUpper = (shiftState != ShiftState.OFF)

        // Optional Number Row (1..0)
        if (showNumberRowInQwerty) {
            val top = currentRow * rowHeight
            val bottom = top + rowHeight
            for (i in 0 until 10) {
                val numStr = JapaneseKanaUtils.QWERTY_NUMBER_ROW[i]
                addKey(
                    type = KeyType.QWERTY_CHAR,
                    left = i * col10Width,
                    top = top,
                    right = (i + 1) * col10Width,
                    bottom = bottom,
                    marginX = marginX,
                    marginY = marginY,
                    label = numStr
                )
            }
            currentRow++
        }

        // Row A: q..p
        val rowATop = currentRow * rowHeight
        val rowABottom = rowATop + rowHeight
        qwertyAlphaTop = rowATop
        val rowASubs = if (showNumberRowInQwerty) {
            JapaneseKanaUtils.QWERTY_ROW_1_SUB_WITH_NUM
        } else {
            JapaneseKanaUtils.QWERTY_ROW_1_SUB_NO_NUM
        }
        for (i in 0 until 10) {
            val ch = JapaneseKanaUtils.QWERTY_ROW_1[i]
            val disp = if (isUpper) ch.uppercase() else ch
            addKey(
                type = KeyType.QWERTY_CHAR,
                left = i * col10Width,
                top = rowATop,
                right = (i + 1) * col10Width,
                bottom = rowABottom,
                marginX = marginX,
                marginY = marginY,
                label = disp,
                subLabel = rowASubs[i]
            )
        }
        currentRow++

        // Row B: a..l (indented by 0.5 key width on each side)
        val rowBTop = currentRow * rowHeight
        val rowBBottom = rowBTop + rowHeight
        val indentB = col10Width * 0.5f
        for (i in 0 until 9) {
            val ch = JapaneseKanaUtils.QWERTY_ROW_2[i]
            val disp = if (isUpper) ch.uppercase() else ch
            val left = indentB + i * col10Width
            addKey(
                type = KeyType.QWERTY_CHAR,
                left = left,
                top = rowBTop,
                right = left + col10Width,
                bottom = rowBBottom,
                marginX = marginX,
                marginY = marginY,
                label = disp,
                subLabel = JapaneseKanaUtils.QWERTY_ROW_2_SUB[i]
            )
        }
        currentRow++

        // Row C: Shift (15%) + z..m (7 * 10%) + Delete (15%)
        val rowCTop = currentRow * rowHeight
        val rowCBottom = rowCTop + rowHeight
        qwertyAlphaBottom = rowCBottom
        val sideWidth = width * 0.15f
        val shiftLabel = when (shiftState) {
            ShiftState.OFF -> "⇧"
            ShiftState.SHIFT_ONCE -> "⬆"
            ShiftState.CAPS_LOCK -> "⇪"
        }
        addKey(
            type = KeyType.SHIFT,
            left = 0f,
            top = rowCTop,
            right = sideWidth,
            bottom = rowCBottom,
            marginX = marginX * 1.3f,
            marginY = marginY,
            label = shiftLabel,
            isAccent = (shiftState == ShiftState.CAPS_LOCK),
            isFunctionKey = true
        )
        for (i in 0 until 7) {
            val ch = JapaneseKanaUtils.QWERTY_ROW_3[i]
            val disp = if (isUpper) ch.uppercase() else ch
            val left = sideWidth + i * col10Width
            addKey(
                type = KeyType.QWERTY_CHAR,
                left = left,
                top = rowCTop,
                right = left + col10Width,
                bottom = rowCBottom,
                marginX = marginX,
                marginY = marginY,
                label = disp,
                subLabel = JapaneseKanaUtils.QWERTY_ROW_3_SUB[i]
            )
        }
        addKey(
            type = KeyType.DELETE,
            left = width - sideWidth,
            top = rowCTop,
            right = width,
            bottom = rowCBottom,
            marginX = marginX * 1.3f,
            marginY = marginY,
            label = "⌫",
            isFunctionKey = true
        )
        currentRow++

        // Row D: Bottom Bar (あa 13% | ?123 11% | , 8.5% | Space 33% | . 8.5% | ◀ 6.5% | ▶ 6.5% | ↵ 13%)
        val rowDTop = currentRow * rowHeight
        val rowDBottom = height
        var x = 0f
        fun nextX(pct: Float): Pair<Float, Float> {
            val l = x
            val r = x + width * pct
            x = r
            return Pair(l, r)
        }

        val (aaL, aaR) = nextX(0.13f)
        addKey(KeyType.MODE_KANA_ALPHA, aaL, rowDTop, aaR, rowDBottom, marginX * 1.2f, marginY * 1.3f, "あa", isPill = true, isFunctionKey = true)

        val (symL, symR) = nextX(0.11f)
        addKey(KeyType.MODE_SYMBOLS, symL, rowDTop, symR, rowDBottom, marginX, marginY, "?123", isFunctionKey = true)

        val showArrowKeys = qwertyCursorControlMode == KeyboardHeightManager.QwertyCursorControlMode.ARROW_KEYS

        val (commaL, commaR) = nextX(0.085f)
        addKey(KeyType.QWERTY_CHAR, commaL, rowDTop, commaR, rowDBottom, marginX, marginY, ",")

        val spacePct = if (showArrowKeys) 0.33f else 0.46f
        val spaceLabel = if (showArrowKeys) "English" else "‹ English ›"
        val (spaceL, spaceR) = nextX(spacePct)
        addKey(KeyType.SPACE, spaceL, rowDTop, spaceR, rowDBottom, marginX * 1.2f, marginY * 1.3f, spaceLabel, isPill = true)

        val (periodL, periodR) = nextX(0.085f)
        addKey(KeyType.QWERTY_CHAR, periodL, rowDTop, periodR, rowDBottom, marginX, marginY, ".")

        if (showArrowKeys) {
            val (leftCurL, leftCurR) = nextX(0.065f)
            addKey(KeyType.CURSOR_LEFT, leftCurL, rowDTop, leftCurR, rowDBottom, marginX * 0.8f, marginY, "◀", isFunctionKey = true)

            val (rightCurL, rightCurR) = nextX(0.065f)
            addKey(KeyType.CURSOR_RIGHT, rightCurL, rowDTop, rightCurR, rowDBottom, marginX * 0.8f, marginY, "▶", isFunctionKey = true)
        }

        val enterLabel = if (isComposingState || hasVoiceComposingState) "確定" else "↵"
        addKey(KeyType.ENTER, x, rowDTop, width, rowDBottom, marginX * 1.2f, marginY * 1.3f, enterLabel, isPill = true, isAccent = true, isFunctionKey = true)

        // Sync normalized 26 alphabet key centers with FutoSwipeEngine
        val alphaHeight = (qwertyAlphaBottom - qwertyAlphaTop).coerceAtLeast(1f)
        val safeWidth = width.coerceAtLeast(1f)
        val keyCenters = FloatArray(52)
        for (k in this) {
            if (isAlphabetSwipeKey(k)) {
                val idx = k.label[0].lowercaseChar() - 'a'
                if (idx in 0..25) {
                    keyCenters[idx * 2] = (k.hitRect.centerX() / safeWidth).coerceIn(0f, 1f)
                    keyCenters[idx * 2 + 1] = ((k.hitRect.centerY() - qwertyAlphaTop) / alphaHeight).coerceIn(0f, 1f)
                }
            }
        }
        try {
            FutoSwipeEngine.getInstance(context).updateLayoutKeys(keyCenters)
        } catch (t: Throwable) {
            android.util.Log.w("FlickKeyboardView", "Failed to update layout keys: ${t.message}")
        }
    }

    /**
     * Gboard-inspired QWERTY Symbols Layout (?123 from QWERTY)
     */
    private fun MutableList<Key>.layoutGboardSymbolsKeys(width: Float, height: Float) {
        val density = resources.displayMetrics.density
        val rowHeight = height / 4f
        val marginX = 2.2f * density
        val marginY = 3.0f * density
        val col10Width = width / 10f

        applyQwertyTypography(rowHeight, marginY, density)

        // Row 0: 1..0
        for (i in 0 until 10) {
            addKey(
                type = KeyType.QWERTY_CHAR,
                left = i * col10Width,
                top = 0f,
                right = (i + 1) * col10Width,
                bottom = rowHeight,
                marginX = marginX,
                marginY = marginY,
                label = JapaneseKanaUtils.QWERTY_SYM_ROW_0[i]
            )
        }

        // Row 1: @ # ¥ % & - + ( ) /
        for (i in 0 until 10) {
            addKey(
                type = KeyType.QWERTY_CHAR,
                left = i * col10Width,
                top = rowHeight,
                right = (i + 1) * col10Width,
                bottom = rowHeight * 2f,
                marginX = marginX,
                marginY = marginY,
                label = JapaneseKanaUtils.QWERTY_SYM_ROW_1[i]
            )
        }

        // Row 2: 1234 Numpad (15%) + * " ' : ; ! ? (70%) + Delete (15%)
        val sideWidth = width * 0.15f
        addKey(KeyType.MODE_NUMPAD, 0f, rowHeight * 2f, sideWidth, rowHeight * 3f, marginX * 1.2f, marginY, "1234", isFunctionKey = true)
        for (i in 0 until 7) {
            val left = sideWidth + i * col10Width
            addKey(
                type = KeyType.QWERTY_CHAR,
                left = left,
                top = rowHeight * 2f,
                right = left + col10Width,
                bottom = rowHeight * 3f,
                marginX = marginX,
                marginY = marginY,
                label = JapaneseKanaUtils.QWERTY_SYM_ROW_2[i]
            )
        }
        addKey(KeyType.DELETE, width - sideWidth, rowHeight * 2f, width, rowHeight * 3f, marginX * 1.2f, marginY, "⌫", isFunctionKey = true)

        // Row 3: Back to ABC/あいう (15%) | , (10%) | Space (40%) | . (10%) | ◀ (6%) | ▶ (6%) | Enter (13%)
        val rowDTop = rowHeight * 3f
        var x = 0f
        fun nextX(pct: Float): Pair<Float, Float> {
            val l = x
            val r = x + width * pct
            x = r
            return Pair(l, r)
        }
        val showArrowKeys = qwertyCursorControlMode == KeyboardHeightManager.QwertyCursorControlMode.ARROW_KEYS
        val (backL, backR) = nextX(0.15f)
        addKey(KeyType.MODE_BACK_KANA, backL, rowDTop, backR, height, marginX * 1.2f, marginY * 1.3f, "ABC", isPill = true, isFunctionKey = true)
        val (commaL, commaR) = nextX(0.10f)
        addKey(KeyType.QWERTY_CHAR, commaL, rowDTop, commaR, height, marginX, marginY, ",")
        val spacePct = if (showArrowKeys) 0.40f else 0.52f
        val spaceLabel = if (showArrowKeys) "␣" else "‹ ␣ ›"
        val (spaceL, spaceR) = nextX(spacePct)
        addKey(KeyType.SPACE, spaceL, rowDTop, spaceR, height, marginX * 1.2f, marginY * 1.3f, spaceLabel, isPill = true)
        val (periodL, periodR) = nextX(0.10f)
        addKey(KeyType.QWERTY_CHAR, periodL, rowDTop, periodR, height, marginX, marginY, ".")
        if (showArrowKeys) {
            val (leftL, leftR) = nextX(0.06f)
            addKey(KeyType.CURSOR_LEFT, leftL, rowDTop, leftR, height, marginX * 0.8f, marginY, "◀", isFunctionKey = true)
            val (rightL, rightR) = nextX(0.06f)
            addKey(KeyType.CURSOR_RIGHT, rightL, rowDTop, rightR, height, marginX * 0.8f, marginY, "▶", isFunctionKey = true)
        }
        val enterLabel = if (isComposingState || hasVoiceComposingState) "確定" else "↵"
        addKey(KeyType.ENTER, x, rowDTop, width, height, marginX * 1.2f, marginY * 1.3f, enterLabel, isPill = true, isAccent = true, isFunctionKey = true)
    }

    /**
     * Gboard-inspired 12-key Numpad Layout (gboard_numpad.png, 5 equal columns 20.0% each)
     */
    private fun MutableList<Key>.layoutGboardNumpadKeys(width: Float, height: Float) {
        val density = resources.displayMetrics.density
        val grid = FlickPopupGeometry.computeTwelveKeyGrid(width, height, density)
        applyTwelveKeyTypography(grid, density)

        // Left Column: +, -, *, あいう/ABC Pill (all share marginX for aligned edges)
        val backLabel = if (previousAlphaOrKanaMode == JapaneseKanaUtils.KeyboardMode.QWERTY) "ABC" else "あいう"
        addTwelveGridKey(grid, col = 0, row = 0, KeyType.QWERTY_CHAR, "+", isFunctionKey = true)
        addTwelveGridKey(grid, col = 0, row = 1, KeyType.QWERTY_CHAR, "-", isFunctionKey = true)
        addTwelveGridKey(grid, col = 0, row = 2, KeyType.QWERTY_CHAR, "*", isFunctionKey = true)
        addTwelveGridKey(grid, col = 0, row = 3, KeyType.MODE_BACK_KANA, backLabel, isPill = true, isFunctionKey = true)

        // Center 3x4 Numpad Grid: 1 2 3 / 4 5 6 / 7 8 9 / , 0 .
        val numLabels = arrayOf(
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            ",", "0", "."
        )
        for (row in 0..3) {
            for (col in 0..2) {
                val index = row * 3 + col
                addTwelveGridKey(grid, col = col + 1, row = row, KeyType.KANA_FLICK, numLabels[index], kanaIndex = index)
            }
        }

        // Right Column: ⌫, /, ␣, ↵ (all share marginX for aligned edges)
        val enterLabel = if (isComposingState || hasVoiceComposingState) "確定" else "↵"
        addTwelveGridKey(grid, col = 4, row = 0, KeyType.DELETE, "⌫", isFunctionKey = true)
        addTwelveGridKey(grid, col = 4, row = 1, KeyType.QWERTY_CHAR, "/", isFunctionKey = true)
        addTwelveGridKey(grid, col = 4, row = 2, KeyType.SPACE, "␣", isFunctionKey = true)
        addTwelveGridKey(grid, col = 4, row = 3, KeyType.ENTER, enterLabel, isPill = true, isAccent = true, isFunctionKey = true)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val defaultRadius = 8f * density
        val currentKeys = keys

        for (key in currentKeys) {
            val radius = if (key.isPill) key.rect.height() / 2f else defaultRadius
            val bgPaint = when {
                key.isPressed -> keyPressedPaint
                key.isAccent -> keyActionBgPaint
                key.isPill && key.isFunctionKey -> keyPillBgPaint
                key.isFunctionKey -> keyFnBgPaint
                else -> keyBgPaint
            }

            canvas.drawRoundRect(key.rect, radius, radius, bgPaint)

            val cx = key.rect.centerX()
            val cy = key.rect.centerY()
            val rowH = key.rect.height()
            val keyIconSize = minOf(currentIconSizePx, key.rect.width() * 0.62f, rowH * 0.62f)

            when (key.type) {
                KeyType.MODE_KANA_ALPHA -> {
                    drawAaPillText(canvas, cx, cy)
                }
                KeyType.MODIFIER_FLICK -> {
                    // Gboard-style ゛゜ on top, 大⇔小 on bottom
                    val topOffset = (keyFnTextPaint.descent() + keyFnTextPaint.ascent()) / 2f
                    canvas.drawText("゛ ゜", cx, cy - rowH * 0.12f - topOffset, keyFnTextPaint)
                    val subOffset = (keyCenterSubTextPaint.descent() + keyCenterSubTextPaint.ascent()) / 2f
                    canvas.drawText("大 ⇔ 小", cx, cy + rowH * 0.24f - subOffset, keyCenterSubTextPaint)
                }
                KeyType.PUNCTUATION_FLICK -> {
                    // Gboard-style 5-direction punctuation graphic (? top, 。 left, 、 center, ! right, … bottom)
                    val subOffset = (keyCenterSubTextPaint.descent() + keyCenterSubTextPaint.ascent()) / 2f
                    val dx = minOf(key.rect.width() * 0.21f, rowH * 0.26f)
                    val dy = rowH * 0.27f
                    canvas.drawText("?", cx, cy - dy - subOffset, keyCenterSubTextPaint)
                    canvas.drawText("。", cx - dx, cy + dy * 0.15f - subOffset, keyCenterSubTextPaint)
                    canvas.drawText("、", cx, cy + dy * 0.15f - subOffset, keyFnTextPaint)
                    canvas.drawText("!", cx + dx, cy + dy * 0.15f - subOffset, keyCenterSubTextPaint)
                    canvas.drawText("…", cx, cy + dy * 1.05f - subOffset, keyCenterSubTextPaint)
                }
                KeyType.DELETE -> {
                    drawBackspaceIcon(canvas, cx, cy, keyIconSize, currentIconStrokeWidthPx, keyFnTextPaint.color)
                }
                KeyType.UNDO -> {
                    val color = if (canUndoState) keyFnTextPaint.color else keyMutedTextPaint.color
                    drawUndoIcon(canvas, cx, cy, keyIconSize, currentIconStrokeWidthPx, color)
                }
                KeyType.CURSOR_LEFT, KeyType.CURSOR_RIGHT -> {
                    drawCursorArrowIcon(
                        canvas, cx, cy, keyIconSize,
                        isLeft = (key.type == KeyType.CURSOR_LEFT),
                        color = keyFnTextPaint.color
                    )
                }
                KeyType.SPACE -> {
                    if (key.label == "␣") {
                        drawSpaceIcon(canvas, cx, cy, keyIconSize, currentIconStrokeWidthPx, keyFnTextPaint.color)
                    } else {
                        drawKeyLabel(canvas, key, density)
                    }
                }
                KeyType.ENTER -> {
                    if (key.label == "↵") {
                        drawReturnIcon(canvas, cx, cy, keyIconSize, currentIconStrokeWidthPx, keyActionTextPaint.color)
                    } else {
                        drawKeyLabel(canvas, key, density)
                    }
                }
                else -> {
                    drawKeyLabel(canvas, key, density)
                }
            }
        }

        // Subtle vertical column dividers in 12-key Flick/Numpad mode (matching Gboard 0x7f150398)
        if (currentMode == JapaneseKanaUtils.KeyboardMode.KANA || currentMode == JapaneseKanaUtils.KeyboardMode.NUMBER) {
            val grid = FlickPopupGeometry.computeTwelveKeyGrid(width.toFloat(), height.toFloat(), density)
            dividerPaint.strokeWidth = (1f * density).coerceAtLeast(1f)
            val topMargin = (14f * density).coerceAtMost(height * 0.15f)
            val botMargin = height - topMargin
            canvas.drawLine(grid.leftDividerX, topMargin, grid.leftDividerX, botMargin, dividerPaint)
            canvas.drawLine(grid.rightDividerX, topMargin, grid.rightDividerX, botMargin, dividerPaint)
        }

        // Draw active swipe path on QWERTY
        if (isSwiping && swipeTrajectory.size >= 2) {
            val path = Path()
            path.moveTo(swipeTrajectory[0].x * width, denormalizeSwipeY(swipeTrajectory[0].y))
            for (i in 1 until swipeTrajectory.size) {
                path.lineTo(swipeTrajectory[i].x * width, denormalizeSwipeY(swipeTrajectory[i].y))
            }
            canvas.drawPath(path, swipePathPaint)
        }

        // Fallback in-canvas popup if PopupWindow is unavailable
        val currentKey = activeKey
        if (!isSwiping && !usingWindowPopup && currentKey != null && isPopupEligible(currentKey)) {
            drawFallbackPopup(canvas, currentKey)
        }
    }

    private fun drawKeyLabel(canvas: Canvas, key: Key, density: Float) {
        val paint = when {
            key.isAccent -> keyActionTextPaint
            key.type == KeyType.UNDO && !canUndoState -> keyMutedTextPaint
            key.isFunctionKey -> keyFnTextPaint
            else -> keyTextPaint
        }
        val cx = key.rect.centerX()
        val cy = key.rect.centerY()
        val textOffset = (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(key.label, cx, cy - textOffset, paint)

        // Top-right secondary symbol on QWERTY keys
        if (key.subLabel.isNotEmpty()) {
            val subX = key.rect.right - 5f * density
            val subY = key.rect.top + keySubTextPaint.textSize + 2.5f * density
            canvas.drawText(key.subLabel, subX, subY, keySubTextPaint)
        }
    }

    private fun drawBackspaceIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        strokeWidth: Float,
        color: Int
    ) {
        val w = size * 0.86f
        val h = size * 0.64f
        val left = cx - w * 0.50f
        val right = cx + w * 0.50f
        val top = cy - h * 0.50f
        val bottom = cy + h * 0.50f
        val kneeX = left + w * 0.26f

        iconStrokePaint.color = color
        iconStrokePaint.strokeWidth = strokeWidth * 0.92f
        iconPath.reset()
        iconPath.moveTo(left, cy)
        iconPath.lineTo(kneeX, top)
        iconPath.lineTo(right, top)
        iconPath.lineTo(right, bottom)
        iconPath.lineTo(kneeX, bottom)
        iconPath.close()
        canvas.drawPath(iconPath, iconStrokePaint)

        val xMid = (kneeX + right) * 0.5f + w * 0.02f
        val r = minOf((right - kneeX) * 0.23f, h * 0.24f)
        canvas.drawLine(xMid - r, cy - r, xMid + r, cy + r, iconStrokePaint)
        canvas.drawLine(xMid - r, cy + r, xMid + r, cy - r, iconStrokePaint)
    }

    private fun drawUndoIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        strokeWidth: Float,
        color: Int
    ) {
        val w = size * 0.60f
        val h = size * 0.52f
        val r = h * 0.34f
        val topY = cy - r * 0.72f
        val botY = topY + 2f * r
        val tipX = cx - w * 0.45f
        val arcRight = cx + w * 0.45f
        val arcCx = arcRight - r

        iconStrokePaint.color = color
        iconStrokePaint.strokeWidth = strokeWidth
        iconPath.reset()
        iconPath.moveTo(tipX, topY)
        iconPath.lineTo(arcCx, topY)
        iconPath.arcTo(arcCx - r, topY, arcRight, botY, -90f, 180f, false)
        iconPath.lineTo(cx - w * 0.14f, botY)

        val head = r * 0.78f
        iconPath.moveTo(tipX + head, topY - head)
        iconPath.lineTo(tipX, topY)
        iconPath.lineTo(tipX + head, topY + head)
        canvas.drawPath(iconPath, iconStrokePaint)
    }

    private fun drawSpaceIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        strokeWidth: Float,
        color: Int
    ) {
        val w = size * 0.64f
        val h = size * 0.22f
        val left = cx - w * 0.50f
        val right = cx + w * 0.50f
        val top = cy - h * 0.50f
        val bottom = cy + h * 0.50f

        iconStrokePaint.color = color
        iconStrokePaint.strokeWidth = strokeWidth
        iconPath.reset()
        iconPath.moveTo(left, top)
        iconPath.lineTo(left, bottom)
        iconPath.lineTo(right, bottom)
        iconPath.lineTo(right, top)
        canvas.drawPath(iconPath, iconStrokePaint)
    }

    private fun drawReturnIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        strokeWidth: Float,
        color: Int
    ) {
        val w = size * 0.68f
        val h = size * 0.48f
        val topStem = cy - h * 0.50f
        val shaftY = cy + h * 0.16f
        val tipX = cx - w * 0.50f
        val stemX = cx + w * 0.50f
        val head = h * 0.34f

        iconStrokePaint.color = color
        iconStrokePaint.strokeWidth = strokeWidth
        iconPath.reset()
        iconPath.moveTo(stemX, topStem)
        iconPath.lineTo(stemX, shaftY)
        iconPath.lineTo(tipX, shaftY)
        iconPath.moveTo(tipX + head, shaftY - head)
        iconPath.lineTo(tipX, shaftY)
        iconPath.lineTo(tipX + head, shaftY + head)
        canvas.drawPath(iconPath, iconStrokePaint)
    }

    private fun drawCursorArrowIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        isLeft: Boolean,
        color: Int
    ) {
        val w = size * 0.34f
        val h = size * 0.42f
        val tipX = if (isLeft) cx - w * 0.50f else cx + w * 0.50f
        val baseX = if (isLeft) cx + w * 0.50f else cx - w * 0.50f

        iconFillPaint.color = color
        iconPath.reset()
        iconPath.moveTo(tipX, cy)
        iconPath.lineTo(baseX, cy - h * 0.50f)
        iconPath.lineTo(baseX, cy + h * 0.50f)
        iconPath.close()
        canvas.drawPath(iconPath, iconFillPaint)
    }

    private fun drawAaPillText(canvas: Canvas, cx: Float, cy: Float) {
        val activePaint = Paint(keyFnTextPaint).apply {
            color = keyTextPaint.color
            isFakeBoldText = true
            textSize = keyFnTextPaint.textSize * 1.05f
        }
        val inactivePaint = Paint(keyFnTextPaint).apply {
            color = keyMutedTextPaint.color
            isFakeBoldText = false
            textSize = keyFnTextPaint.textSize * 0.90f
        }
        val isKana = (currentMode == JapaneseKanaUtils.KeyboardMode.KANA)
        val pLeft = if (isKana) activePaint else inactivePaint
        val pRight = if (!isKana) activePaint else inactivePaint
        val offset = (activePaint.descent() + activePaint.ascent()) / 2f
        val gap = activePaint.textSize * 0.48f
        canvas.drawText("あ", cx - gap, cy - offset, pLeft)
        canvas.drawText("a", cx + gap, cy - offset, pRight)
    }

    private fun isPopupEligible(key: Key): Boolean {
        return key.type == KeyType.KANA_FLICK ||
            key.type == KeyType.MODIFIER_FLICK ||
            key.type == KeyType.PUNCTUATION_FLICK ||
            key.type == KeyType.QWERTY_CHAR
    }

    private fun notifyKeyTouchUpOrCancel() {
        if (isCharKeyTouchNotified) {
            isCharKeyTouchNotified = false
            listener?.onKeyTouchUpOrCancel()
        }
    }

    fun dismissPopup() {
        handler.removeCallbacksAndMessages(null)
        deleteRepeatStartTimeMs = 0L
        deleteRepeatCount = 0
        lastRepeatHapticTimeMs = 0L
        flickPopupWindow.dismiss()
        usingWindowPopup = false
        longPressTriggered = false
        spacebarSwipeTracker.reset()
        activeKey?.isPressed = false
        activeKey = null
        activeDirection = JapaneseKanaUtils.FlickDirection.CENTER
        notifyKeyTouchUpOrCancel()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dismissPopup()
    }

    private fun drawFallbackPopup(canvas: Canvas, key: Key) {
        val cx = key.rect.centerX()
        val rowH = key.rect.height()
        val cy = key.rect.centerY() - (rowH * 0.95f)
        val density = resources.displayMetrics.density
        val petalRadius = (rowH * 0.34f).coerceIn(16f * density, 26f * density)

        if (key.type == KeyType.QWERTY_CHAR) {
            val preview = if (longPressTriggered && key.subLabel.isNotEmpty()) key.subLabel else key.label
            canvas.drawCircle(cx, cy, petalRadius * 1.15f, popupBgPaint)
            canvas.drawText(preview, cx, cy + 14f, popupTextPaint)
            return
        }

        val distance = (rowH * 0.52f).coerceIn(24f * density, 38f * density)
        val directions = listOf(
            Triple(JapaneseKanaUtils.FlickDirection.CENTER, cx, cy),
            Triple(JapaneseKanaUtils.FlickDirection.LEFT, cx - distance, cy),
            Triple(JapaneseKanaUtils.FlickDirection.UP, cx, cy - distance),
            Triple(JapaneseKanaUtils.FlickDirection.RIGHT, cx + distance, cy),
            Triple(JapaneseKanaUtils.FlickDirection.DOWN, cx, cy + distance)
        )

        for ((dir, px, py) in directions) {
            val str = JapaneseKanaUtils.getFlickString(key.kanaIndex, dir, currentMode)
            if (str.isEmpty()) continue
            val isSelected = (dir == activeDirection)
            val paint = if (isSelected) popupActiveBgPaint else popupBgPaint
            canvas.drawCircle(px, py, petalRadius, paint)
            canvas.drawText(str, px, py + 14f, popupTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(repeatRunnable)
                handler.removeCallbacks(qwertyLongPressRunnable)
                deleteRepeatStartTimeMs = 0L
                deleteRepeatCount = 0
                lastRepeatHapticTimeMs = 0L
                touchStartX = x
                touchStartY = y
                activeDirection = JapaneseKanaUtils.FlickDirection.CENTER
                longPressTriggered = false
                isSwiping = false
                swipeTrajectory.clear()
                spacebarSwipeTracker.reset()

                val currentKeys = keys
                val hitKey = currentKeys.firstOrNull { it.hitRect.contains(x, y) }
                if (hitKey != null) {
                    activeKey = hitKey
                    hitKey.isPressed = true
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                    if (hitKey.type == KeyType.KANA_FLICK ||
                        hitKey.type == KeyType.QWERTY_CHAR ||
                        hitKey.type == KeyType.MODIFIER_FLICK ||
                        hitKey.type == KeyType.PUNCTUATION_FLICK
                    ) {
                        isCharKeyTouchNotified = true
                        listener?.onKeyTouchDown()
                    }

                    if (isSpacebarSwipeEnabledForCurrentMode() && hitKey.type == KeyType.SPACE) {
                        spacebarSwipeTracker.start(x, y)
                    }

                    if (currentMode == JapaneseKanaUtils.KeyboardMode.QWERTY &&
                        isAlphabetSwipeKey(hitKey) &&
                        KeyboardHeightManager.isQwertySwipeTypingEnabled(context)
                    ) {
                        val normX = (x / width.toFloat()).coerceIn(0f, 1f)
                        val normY = normalizeSwipeY(y)
                        swipeTrajectory.add(FutoSwipeEngine.SwipePoint(normX, normY, event.eventTime))
                    }

                    if (hitKey.type == KeyType.DELETE) {
                        handler.postDelayed(repeatRunnable, DeleteRepeatAccelerator.INITIAL_DELAY_MS)
                    } else if (hitKey.type == KeyType.CURSOR_LEFT || hitKey.type == KeyType.CURSOR_RIGHT) {
                        handler.postDelayed(repeatRunnable, 400L)
                    } else if (hitKey.type == KeyType.QWERTY_CHAR && hitKey.subLabel.isNotEmpty()) {
                        handler.postDelayed(qwertyLongPressRunnable, 300L)
                    }

                    usingWindowPopup = when (hitKey.type) {
                        KeyType.KANA_FLICK, KeyType.MODIFIER_FLICK, KeyType.PUNCTUATION_FLICK -> {
                            flickPopupWindow.show(
                                anchor = this,
                                keyCenterXInView = hitKey.rect.centerX(),
                                keyCenterYInView = hitKey.rect.centerY(),
                                rowHeight = hitKey.rect.height(),
                                kanaIndex = hitKey.kanaIndex,
                                mode = currentMode,
                                direction = activeDirection,
                                singleLabel = null
                            )
                        }
                        KeyType.QWERTY_CHAR -> {
                            flickPopupWindow.show(
                                anchor = this,
                                keyCenterXInView = hitKey.rect.centerX(),
                                keyCenterYInView = hitKey.rect.centerY(),
                                rowHeight = hitKey.rect.height(),
                                kanaIndex = -1,
                                mode = currentMode,
                                direction = activeDirection,
                                singleLabel = hitKey.label
                            )
                        }
                        else -> false
                    }
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val currentKey = activeKey ?: return false
                val dx = x - touchStartX
                val dy = y - touchStartY
                val dist = sqrt(dx * dx + dy * dy)

                if (isSpacebarSwipeEnabledForCurrentMode() && currentKey.type == KeyType.SPACE) {
                    val steps = spacebarSwipeTracker.move(x, y)
                    if (steps != 0) {
                        currentKey.isPressed = false
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        if (steps > 0) {
                            repeat(steps) { listener?.onCursorRight() }
                        } else {
                            repeat(-steps) { listener?.onCursorLeft() }
                        }
                        invalidate()
                    }
                    if (spacebarSwipeTracker.isSwiping) {
                        return true
                    }
                }

                if (currentMode == JapaneseKanaUtils.KeyboardMode.QWERTY &&
                    isAlphabetSwipeKey(currentKey) &&
                    KeyboardHeightManager.isQwertySwipeTypingEnabled(context)
                ) {
                    if (!isSwiping && dist >= swipeThresholdPx && !longPressTriggered) {
                        isSwiping = true
                        handler.removeCallbacks(qwertyLongPressRunnable)
                        flickPopupWindow.dismiss()
                        usingWindowPopup = false
                        currentKey.isPressed = false
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }

                    if (isSwiping) {
                        val normX = (x / width.toFloat()).coerceIn(0f, 1f)
                        val normY = normalizeSwipeY(y)
                        swipeTrajectory.add(FutoSwipeEngine.SwipePoint(normX, normY, event.eventTime))
                        invalidate()
                        return true
                    }
                }

                if (currentKey.type == KeyType.KANA_FLICK || currentKey.type == KeyType.MODIFIER_FLICK || currentKey.type == KeyType.PUNCTUATION_FLICK) {
                    val newDir = if (dist < flickThresholdPx) {
                        JapaneseKanaUtils.FlickDirection.CENTER
                    } else {
                        val angle = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).toFloat()
                        when {
                            angle in -45f..45f -> JapaneseKanaUtils.FlickDirection.RIGHT
                            angle in 45f..135f -> JapaneseKanaUtils.FlickDirection.UP
                            angle in -135f..-45f -> JapaneseKanaUtils.FlickDirection.DOWN
                            else -> JapaneseKanaUtils.FlickDirection.LEFT
                        }
                    }

                    if (newDir != activeDirection) {
                        activeDirection = newDir
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (usingWindowPopup) {
                            flickPopupWindow.updateDirection(newDir)
                        } else {
                            invalidate()
                        }
                    }
                } else if (currentKey.type == KeyType.QWERTY_CHAR && currentKey.subLabel.isNotEmpty()) {
                    // Allow upward flick on QWERTY key to quickly select its top-right symbol/number
                    if (-dy > flickThresholdPx && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                        if (!longPressTriggered) {
                            longPressTriggered = true
                            handler.removeCallbacks(qwertyLongPressRunnable)
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            if (usingWindowPopup) {
                                flickPopupWindow.updateSingleLabel(currentKey.subLabel)
                            } else {
                                invalidate()
                            }
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeatRunnable)
                handler.removeCallbacks(qwertyLongPressRunnable)
                flickPopupWindow.dismiss()
                usingWindowPopup = false
                val wasSpaceSwiping = spacebarSwipeTracker.isSwiping
                spacebarSwipeTracker.reset()

                if (wasSpaceSwiping) {
                    activeKey?.isPressed = false
                    activeKey = null
                    longPressTriggered = false
                    activeDirection = JapaneseKanaUtils.FlickDirection.CENTER
                    notifyKeyTouchUpOrCancel()
                    invalidate()
                    return true
                }

                if (isSwiping) {
                    val normX = (x / width.toFloat()).coerceIn(0f, 1f)
                    val normY = normalizeSwipeY(y)
                    swipeTrajectory.add(FutoSwipeEngine.SwipePoint(normX, normY, event.eventTime))
                    val trajectoryCopy = swipeTrajectory.toList()
                    isSwiping = false
                    swipeTrajectory.clear()
                    activeKey?.isPressed = false
                    activeKey = null
                    longPressTriggered = false
                    activeDirection = JapaneseKanaUtils.FlickDirection.CENTER
                    invalidate()
                    listener?.onSwipeInput(trajectoryCopy)
                    notifyKeyTouchUpOrCancel()
                    return true
                }

                val key = activeKey
                if (key != null) {
                    key.isPressed = false
                    handleKeyRelease(key)
                }
                deleteRepeatStartTimeMs = 0L
                deleteRepeatCount = 0
                lastRepeatHapticTimeMs = 0L
                longPressTriggered = false
                activeKey = null
                activeDirection = JapaneseKanaUtils.FlickDirection.CENTER
                notifyKeyTouchUpOrCancel()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dismissPopup()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleKeyRelease(key: Key) {
        when (key.type) {
            KeyType.KANA_FLICK, KeyType.PUNCTUATION_FLICK -> {
                val text = JapaneseKanaUtils.getFlickString(key.kanaIndex, activeDirection, currentMode)
                if (text.isNotEmpty()) {
                    listener?.onTextChar(text)
                }
            }
            KeyType.MODIFIER_FLICK -> {
                listener?.onModifierPressed(activeDirection)
            }
            KeyType.QWERTY_CHAR -> {
                val out = if (longPressTriggered && key.subLabel.isNotEmpty()) {
                    key.subLabel
                } else {
                    key.label
                }
                if (out.isNotEmpty()) {
                    listener?.onTextChar(out)
                }
                if (shiftState == ShiftState.SHIFT_ONCE && !longPressTriggered && key.label.length == 1 && key.label[0].isLetter()) {
                    shiftState = ShiftState.OFF
                    layoutKeys(width.toFloat(), height.toFloat())
                }
            }
            KeyType.SHIFT -> {
                val now = System.currentTimeMillis()
                shiftState = when (shiftState) {
                    ShiftState.OFF -> {
                        lastShiftTapTimeMs = now
                        ShiftState.SHIFT_ONCE
                    }
                    ShiftState.SHIFT_ONCE -> {
                        if (now - lastShiftTapTimeMs < 400L) {
                            ShiftState.CAPS_LOCK
                        } else {
                            ShiftState.OFF
                        }
                    }
                    ShiftState.CAPS_LOCK -> ShiftState.OFF
                }
                layoutKeys(width.toFloat(), height.toFloat())
            }
            KeyType.MODE_KANA_ALPHA -> {
                val target = if (currentMode == JapaneseKanaUtils.KeyboardMode.KANA) {
                    JapaneseKanaUtils.KeyboardMode.QWERTY
                } else {
                    JapaneseKanaUtils.KeyboardMode.KANA
                }
                setMode(target)
            }
            KeyType.MODE_SYMBOLS -> {
                setMode(JapaneseKanaUtils.KeyboardMode.QWERTY_SYMBOLS)
            }
            KeyType.MODE_NUMPAD -> {
                setMode(JapaneseKanaUtils.KeyboardMode.NUMBER)
            }
            KeyType.MODE_BACK_KANA -> {
                setMode(previousAlphaOrKanaMode)
            }
            KeyType.UNDO -> listener?.onUndoPressed()
            KeyType.CURSOR_LEFT -> listener?.onCursorLeft()
            KeyType.CURSOR_RIGHT -> listener?.onCursorRight()
            KeyType.DELETE -> if (deleteRepeatCount == 0) listener?.onDeletePressed(1)
            KeyType.SPACE -> listener?.onSpacePressed()
            KeyType.ENTER -> listener?.onEnterPressed()
            KeyType.GLOBE -> listener?.onGlobePressed()
            KeyType.DICT -> listener?.onDictPressed()
        }
    }
}
