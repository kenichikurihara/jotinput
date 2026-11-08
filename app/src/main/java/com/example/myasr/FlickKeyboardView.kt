package com.example.myasr

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
        fun onDeletePressed()
        fun onSpacePressed()
        fun onEnterPressed()
        fun onGlobePressed()
        fun onDictPressed()
        fun onCursorLeft() {}
        fun onCursorRight() {}
        fun onUndoPressed() {}
        fun onSwipeInput(trajectory: List<FutoSwipeEngine.SwipePoint>) {}
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

    private val keys = ArrayList<Key>()
    private var activeKey: Key? = null
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var activeDirection = JapaneseKanaUtils.FlickDirection.CENTER
    private var longPressTriggered = false
    private val flickPopupWindow = FlickPopupWindow(context)
    private var usingWindowPopup = false

    private val handler = Handler(Looper.getMainLooper())
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val key = activeKey ?: return
            when (key.type) {
                KeyType.DELETE -> {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    listener?.onDeletePressed()
                    handler.postDelayed(this, 65L)
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

    private val flickThresholdPx = 26f * resources.displayMetrics.density

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        showNumberRowInQwerty = KeyboardHeightManager.isQwertyNumberRowEnabled(context)
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
            keyMutedTextPaint.color = Color.parseColor("#5F6368")
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
            keyMutedTextPaint.color = Color.parseColor("#9AA0A6")
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
        val savedMode = KeyboardHeightManager.getLastKeyboardMode(context)
        var changed = false
        if (showNumberRowInQwerty != numRow) {
            showNumberRowInQwerty = numRow
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
        keys.clear()
        if (width <= 0 || height <= 0) return

        when (currentMode) {
            JapaneseKanaUtils.KeyboardMode.KANA -> layoutGboardFlickKeys(width, height)
            JapaneseKanaUtils.KeyboardMode.QWERTY -> layoutGboardQwertyKeys(width, height)
            JapaneseKanaUtils.KeyboardMode.QWERTY_SYMBOLS -> layoutGboardSymbolsKeys(width, height)
            JapaneseKanaUtils.KeyboardMode.NUMBER -> layoutGboardNumpadKeys(width, height)
        }
    }

    private fun addKey(
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
        isFunctionKey: Boolean = false
    ) {
        val visualRect = RectF(left + marginX, top + marginY, right - marginX, bottom - marginY)
        val hitRect = RectF(left, top, right, bottom)
        keys.add(
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

    /**
     * Gboard-inspired 12-key Flick-Only Japanese Layout:
     * - Left Function Column (15.5%): Undo (↶), Cursor Left (◀), ?123, Mode Toggle Pill (あa)
     * - Center 3x4 Grid (69.0%): Clean primary labels only (あ か さ / た な は / ま や ら / ゛゜大⇔小 わ 、。？！…)
     * - Right Function Column (15.5%): Delete (⌫), Cursor Right (▶), Space (␣ / 変換), Enter Pill (↵ / 確定)
     */
    private fun layoutGboardFlickKeys(width: Float, height: Float) {
        val density = resources.displayMetrics.density
        val rowHeight = height / 4f
        val colFnWidth = width * 0.155f
        val colKanaWidth = (width - colFnWidth * 2f) / 3f
        val marginX = 2.5f * density
        val marginY = 2.5f * density

        keyTextPaint.textSize = (rowHeight * 0.44f).coerceIn(24f, 48f)
        keyFnTextPaint.textSize = (rowHeight * 0.34f).coerceIn(18f, 36f)
        keyMutedTextPaint.textSize = keyFnTextPaint.textSize
        keyActionTextPaint.textSize = (rowHeight * 0.35f).coerceIn(20f, 40f)
        keyCenterSubTextPaint.textSize = (rowHeight * 0.20f).coerceIn(13f, 22f)
        popupTextPaint.textSize = (rowHeight * 0.36f).coerceIn(20f, 38f)

        // Col 0: Left Function Column
        addKey(KeyType.UNDO, 0f, 0f, colFnWidth, rowHeight, marginX, marginY, "↶", isFunctionKey = true)
        addKey(KeyType.CURSOR_LEFT, 0f, rowHeight, colFnWidth, rowHeight * 2f, marginX, marginY, "◀", isFunctionKey = true)
        addKey(KeyType.MODE_NUMPAD, 0f, rowHeight * 2f, colFnWidth, rowHeight * 3f, marginX, marginY, "?123", isFunctionKey = true)
        addKey(KeyType.MODE_KANA_ALPHA, 0f, rowHeight * 3f, colFnWidth, rowHeight * 4f, marginX * 1.4f, marginY * 1.6f, "あa", isPill = true, isFunctionKey = true)

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
                val left = colFnWidth + col * colKanaWidth
                val top = row * rowHeight
                val right = left + colKanaWidth
                val bottom = top + rowHeight

                val keyType = when (index) {
                    9 -> KeyType.MODIFIER_FLICK
                    11 -> KeyType.PUNCTUATION_FLICK
                    else -> KeyType.KANA_FLICK
                }

                addKey(
                    type = keyType,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    marginX = marginX,
                    marginY = marginY,
                    label = mainLabels[index],
                    kanaIndex = index
                )
            }
        }

        // Col 4: Right Function Column
        val rightLeft = width - colFnWidth
        val spaceLabel = "␣"
        val enterLabel = if (isComposingState || hasVoiceComposingState) "確定" else "↵"

        addKey(KeyType.DELETE, rightLeft, 0f, width, rowHeight, marginX, marginY, "⌫", isFunctionKey = true)
        addKey(KeyType.CURSOR_RIGHT, rightLeft, rowHeight, width, rowHeight * 2f, marginX, marginY, "▶", isFunctionKey = true)
        addKey(KeyType.SPACE, rightLeft, rowHeight * 2f, width, rowHeight * 3f, marginX, marginY, spaceLabel, isFunctionKey = true)
        addKey(KeyType.ENTER, rightLeft, rowHeight * 3f, width, rowHeight * 4f, marginX * 1.4f, marginY * 1.6f, enterLabel, isPill = true, isAccent = true, isFunctionKey = true)
    }

    /**
     * Gboard-inspired English QWERTY Layout:
     * - Optional Row 0 (1..0) when showNumberRowInQwerty == true
     * - Row A: q w e r t y u i o p (with top-right symbols or numbers)
     * - Row B: a s d f g h j k l (5% left/right indent, top-right symbols)
     * - Row C: Shift (15%), z x c v b n m (10% each), Delete (15%)
     * - Row D: あa pill (13%), ?123 (11%), ',' (9%), Space pill (34%), '.' (9%), ◀ (5.5%), ▶ (5.5%), Enter pill (13%)
     */
    private fun layoutGboardQwertyKeys(width: Float, height: Float) {
        val density = resources.displayMetrics.density
        val totalRows = if (showNumberRowInQwerty) 5 else 4
        val rowHeight = height / totalRows.toFloat()
        val marginX = 2.2f * density
        val marginY = 3.0f * density

        keyTextPaint.textSize = (rowHeight * 0.45f).coerceIn(20f, 42f)
        keyFnTextPaint.textSize = (rowHeight * 0.34f).coerceIn(16f, 32f)
        keyMutedTextPaint.textSize = keyFnTextPaint.textSize
        keyActionTextPaint.textSize = (rowHeight * 0.36f).coerceIn(18f, 36f)
        keySubTextPaint.textSize = (rowHeight * 0.22f).coerceIn(11f, 20f)
        keyCenterSubTextPaint.textSize = keySubTextPaint.textSize
        popupTextPaint.textSize = (rowHeight * 0.42f).coerceIn(22f, 40f)

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

        val (commaL, commaR) = nextX(0.085f)
        addKey(KeyType.QWERTY_CHAR, commaL, rowDTop, commaR, rowDBottom, marginX, marginY, ",")

        val (spaceL, spaceR) = nextX(0.33f)
        addKey(KeyType.SPACE, spaceL, rowDTop, spaceR, rowDBottom, marginX * 1.2f, marginY * 1.3f, "English", isPill = true)

        val (periodL, periodR) = nextX(0.085f)
        addKey(KeyType.QWERTY_CHAR, periodL, rowDTop, periodR, rowDBottom, marginX, marginY, ".")

        val (leftCurL, leftCurR) = nextX(0.065f)
        addKey(KeyType.CURSOR_LEFT, leftCurL, rowDTop, leftCurR, rowDBottom, marginX * 0.8f, marginY, "◀", isFunctionKey = true)

        val (rightCurL, rightCurR) = nextX(0.065f)
        addKey(KeyType.CURSOR_RIGHT, rightCurL, rowDTop, rightCurR, rowDBottom, marginX * 0.8f, marginY, "▶", isFunctionKey = true)

        val enterLabel = if (isComposingState || hasVoiceComposingState) "確定" else "↵"
        addKey(KeyType.ENTER, x, rowDTop, width, rowDBottom, marginX * 1.2f, marginY * 1.3f, enterLabel, isPill = true, isAccent = true, isFunctionKey = true)
    }

    /**
     * Gboard-inspired QWERTY Symbols Layout (?123 from QWERTY)
     */
    private fun layoutGboardSymbolsKeys(width: Float, height: Float) {
        val density = resources.displayMetrics.density
        val rowHeight = height / 4f
        val marginX = 2.2f * density
        val marginY = 3.0f * density
        val col10Width = width / 10f

        keyTextPaint.textSize = (rowHeight * 0.42f).coerceIn(20f, 40f)
        keyFnTextPaint.textSize = (rowHeight * 0.32f).coerceIn(16f, 32f)
        keyActionTextPaint.textSize = (rowHeight * 0.36f).coerceIn(18f, 36f)
        keySubTextPaint.textSize = (rowHeight * 0.20f).coerceIn(11f, 18f)

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
        val (backL, backR) = nextX(0.15f)
        addKey(KeyType.MODE_BACK_KANA, backL, rowDTop, backR, height, marginX * 1.2f, marginY * 1.3f, "ABC", isPill = true, isFunctionKey = true)
        val (commaL, commaR) = nextX(0.10f)
        addKey(KeyType.QWERTY_CHAR, commaL, rowDTop, commaR, height, marginX, marginY, ",")
        val (spaceL, spaceR) = nextX(0.40f)
        addKey(KeyType.SPACE, spaceL, rowDTop, spaceR, height, marginX * 1.2f, marginY * 1.3f, "␣", isPill = true)
        val (periodL, periodR) = nextX(0.10f)
        addKey(KeyType.QWERTY_CHAR, periodL, rowDTop, periodR, height, marginX, marginY, ".")
        val (leftL, leftR) = nextX(0.06f)
        addKey(KeyType.CURSOR_LEFT, leftL, rowDTop, leftR, height, marginX * 0.8f, marginY, "◀", isFunctionKey = true)
        val (rightL, rightR) = nextX(0.06f)
        addKey(KeyType.CURSOR_RIGHT, rightL, rowDTop, rightR, height, marginX * 0.8f, marginY, "▶", isFunctionKey = true)
        val enterLabel = if (isComposingState || hasVoiceComposingState) "確定" else "↵"
        addKey(KeyType.ENTER, x, rowDTop, width, height, marginX * 1.2f, marginY * 1.3f, enterLabel, isPill = true, isAccent = true, isFunctionKey = true)
    }

    /**
     * Gboard-inspired 12-key Numpad Layout (gboard_numpad.png)
     */
    private fun layoutGboardNumpadKeys(width: Float, height: Float) {
        val density = resources.displayMetrics.density
        val rowHeight = height / 4f
        val colFnWidth = width * 0.155f
        val colNumWidth = (width - colFnWidth * 2f) / 3f
        val marginX = 2.5f * density
        val marginY = 2.5f * density

        keyTextPaint.textSize = (rowHeight * 0.44f).coerceIn(24f, 48f)
        keyFnTextPaint.textSize = (rowHeight * 0.34f).coerceIn(18f, 36f)
        keyActionTextPaint.textSize = (rowHeight * 0.35f).coerceIn(20f, 40f)

        // Left Column: +, -, *, あいう/ABC Pill
        val backLabel = if (previousAlphaOrKanaMode == JapaneseKanaUtils.KeyboardMode.QWERTY) "ABC" else "あいう"
        addKey(KeyType.QWERTY_CHAR, 0f, 0f, colFnWidth, rowHeight, marginX, marginY, "+", isFunctionKey = true)
        addKey(KeyType.QWERTY_CHAR, 0f, rowHeight, colFnWidth, rowHeight * 2f, marginX, marginY, "-", isFunctionKey = true)
        addKey(KeyType.QWERTY_CHAR, 0f, rowHeight * 2f, colFnWidth, rowHeight * 3f, marginX, marginY, "*", isFunctionKey = true)
        addKey(KeyType.MODE_BACK_KANA, 0f, rowHeight * 3f, colFnWidth, rowHeight * 4f, marginX * 1.2f, marginY * 1.5f, backLabel, isPill = true, isFunctionKey = true)

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
                val left = colFnWidth + col * colNumWidth
                val top = row * rowHeight
                addKey(
                    type = KeyType.KANA_FLICK,
                    left = left,
                    top = top,
                    right = left + colNumWidth,
                    bottom = top + rowHeight,
                    marginX = marginX,
                    marginY = marginY,
                    label = numLabels[index],
                    kanaIndex = index
                )
            }
        }

        // Right Column: %, ␣, ⌫, ↵
        val rightLeft = width - colFnWidth
        val enterLabel = if (isComposingState || hasVoiceComposingState) "確定" else "↵"
        addKey(KeyType.DELETE, rightLeft, 0f, width, rowHeight, marginX, marginY, "⌫", isFunctionKey = true)
        addKey(KeyType.QWERTY_CHAR, rightLeft, rowHeight, width, rowHeight * 2f, marginX, marginY, "/", isFunctionKey = true)
        addKey(KeyType.SPACE, rightLeft, rowHeight * 2f, width, rowHeight * 3f, marginX, marginY, "␣", isFunctionKey = true)
        addKey(KeyType.ENTER, rightLeft, rowHeight * 3f, width, rowHeight * 4f, marginX * 1.4f, marginY * 1.6f, enterLabel, isPill = true, isAccent = true, isFunctionKey = true)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val defaultRadius = 8f * density

        for (key in keys) {
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
                    val dx = key.rect.width() * 0.24f
                    val dy = rowH * 0.26f
                    canvas.drawText("?", cx, cy - dy - subOffset, keyCenterSubTextPaint)
                    canvas.drawText("。", cx - dx, cy + dy * 0.15f - subOffset, keyCenterSubTextPaint)
                    canvas.drawText("、", cx, cy + dy * 0.15f - subOffset, keyFnTextPaint)
                    canvas.drawText("!", cx + dx, cy + dy * 0.15f - subOffset, keyCenterSubTextPaint)
                    canvas.drawText("…", cx, cy + dy * 1.05f - subOffset, keyCenterSubTextPaint)
                }
                else -> {
                    val paint = when {
                        key.isAccent -> keyActionTextPaint
                        key.type == KeyType.UNDO && !canUndoState -> keyMutedTextPaint
                        key.isFunctionKey -> keyFnTextPaint
                        else -> keyTextPaint
                    }
                    val textOffset = (paint.descent() + paint.ascent()) / 2f
                    canvas.drawText(key.label, cx, cy - textOffset, paint)

                    // Top-right secondary symbol on QWERTY keys
                    if (key.subLabel.isNotEmpty()) {
                        val subX = key.rect.right - 5f * density
                        val subY = key.rect.top + keySubTextPaint.textSize + 2.5f * density
                        canvas.drawText(key.subLabel, subX, subY, keySubTextPaint)
                    }
                }
            }
        }

        // Subtle vertical column dividers in 12-key Flick/Numpad mode (matching Gboard)
        if (currentMode == JapaneseKanaUtils.KeyboardMode.KANA || currentMode == JapaneseKanaUtils.KeyboardMode.NUMBER) {
            val colFnWidth = width * 0.155f
            val topMargin = 8f * density
            val botMargin = height - 8f * density
            canvas.drawLine(colFnWidth, topMargin, colFnWidth, botMargin, dividerPaint)
            canvas.drawLine(width - colFnWidth, topMargin, width - colFnWidth, botMargin, dividerPaint)
        }

        // Draw active swipe path on QWERTY
        if (isSwiping && swipeTrajectory.size >= 2) {
            val path = Path()
            path.moveTo(swipeTrajectory[0].x * width, swipeTrajectory[0].y * height)
            for (i in 1 until swipeTrajectory.size) {
                path.lineTo(swipeTrajectory[i].x * width, swipeTrajectory[i].y * height)
            }
            canvas.drawPath(path, swipePathPaint)
        }

        // Fallback in-canvas popup if PopupWindow is unavailable
        val currentKey = activeKey
        if (!isSwiping && !usingWindowPopup && currentKey != null && isPopupEligible(currentKey)) {
            drawFallbackPopup(canvas, currentKey)
        }
    }

    private fun drawAaPillText(canvas: Canvas, cx: Float, cy: Float) {
        val activePaint = Paint(keyFnTextPaint).apply {
            color = Color.parseColor("#202124")
            isFakeBoldText = true
            textSize = keyFnTextPaint.textSize * 1.05f
        }
        val inactivePaint = Paint(keyFnTextPaint).apply {
            color = Color.parseColor("#80868B")
            isFakeBoldText = false
            textSize = keyFnTextPaint.textSize * 0.90f
        }
        val isKana = (currentMode == JapaneseKanaUtils.KeyboardMode.KANA)
        val pLeft = if (isKana) activePaint else inactivePaint
        val pRight = if (!isKana) activePaint else inactivePaint
        val offset = (activePaint.descent() + activePaint.ascent()) / 2f
        val gap = activePaint.textSize * 0.46f
        canvas.drawText("あ", cx - gap, cy - offset, pLeft)
        canvas.drawText("a", cx + gap, cy - offset, pRight)
    }

    private fun isPopupEligible(key: Key): Boolean {
        return key.type == KeyType.KANA_FLICK ||
            key.type == KeyType.MODIFIER_FLICK ||
            key.type == KeyType.PUNCTUATION_FLICK ||
            key.type == KeyType.QWERTY_CHAR
    }

    fun dismissPopup() {
        handler.removeCallbacks(repeatRunnable)
        handler.removeCallbacks(qwertyLongPressRunnable)
        flickPopupWindow.dismiss()
        usingWindowPopup = false
        longPressTriggered = false
        activeKey?.isPressed = false
        activeKey = null
        activeDirection = JapaneseKanaUtils.FlickDirection.CENTER
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
                touchStartX = x
                touchStartY = y
                activeDirection = JapaneseKanaUtils.FlickDirection.CENTER
                longPressTriggered = false
                isSwiping = false
                swipeTrajectory.clear()

                val hitKey = keys.firstOrNull { it.hitRect.contains(x, y) }
                if (hitKey != null) {
                    activeKey = hitKey
                    hitKey.isPressed = true
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                    if (currentMode == JapaneseKanaUtils.KeyboardMode.QWERTY &&
                        hitKey.type == KeyType.QWERTY_CHAR &&
                        KeyboardHeightManager.isQwertySwipeTypingEnabled(context)
                    ) {
                        val normX = (x / width.toFloat()).coerceIn(0f, 1f)
                        val normY = (y / height.toFloat()).coerceIn(0f, 1f)
                        swipeTrajectory.add(FutoSwipeEngine.SwipePoint(normX, normY, event.eventTime))
                    }

                    if (hitKey.type == KeyType.DELETE || hitKey.type == KeyType.CURSOR_LEFT || hitKey.type == KeyType.CURSOR_RIGHT) {
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

                if (currentMode == JapaneseKanaUtils.KeyboardMode.QWERTY &&
                    currentKey.type == KeyType.QWERTY_CHAR &&
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
                        val normY = (y / height.toFloat()).coerceIn(0f, 1f)
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

                if (isSwiping) {
                    val normX = (x / width.toFloat()).coerceIn(0f, 1f)
                    val normY = (y / height.toFloat()).coerceIn(0f, 1f)
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
                    return true
                }

                val key = activeKey
                if (key != null) {
                    key.isPressed = false
                    handleKeyRelease(key)
                }
                longPressTriggered = false
                activeKey = null
                activeDirection = JapaneseKanaUtils.FlickDirection.CENTER
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
            KeyType.DELETE -> listener?.onDeletePressed()
            KeyType.SPACE -> listener?.onSpacePressed()
            KeyType.ENTER -> listener?.onEnterPressed()
            KeyType.GLOBE -> listener?.onGlobePressed()
            KeyType.DICT -> listener?.onDictPressed()
        }
    }
}
