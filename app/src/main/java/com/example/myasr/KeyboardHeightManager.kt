package com.example.myasr

import android.content.Context
import android.content.res.Configuration

object KeyboardHeightManager {

    const val PREFS_NAME = "ime_preferences"
    private const val KEY_HEIGHT_SCALE = "keyboard_height_scale"
    private const val KEY_QWERTY_NUMBER_ROW = "qwerty_show_number_row"
    private const val KEY_QWERTY_SWIPE_TYPING = "qwerty_swipe_typing_enabled"
    private const val KEY_ENGLISH_AUTO_SPACE = "english_auto_space_enabled"
    private const val KEY_ENGLISH_AUTO_PERIOD = "english_auto_period_enabled"
    private const val KEY_JAPANESE_PERIOD_SUGGESTION = "japanese_period_suggestion_enabled"
    private const val KEY_LAST_KEYBOARD_MODE = "last_keyboard_mode"
    const val KEY_SLM_WEIGHT_ALPHA = "slm_weight_alpha"
    const val DEFAULT_SLM_WEIGHT_ALPHA = 0.20f
    const val KEY_SLM_LAMBDA_OBS = "slm_lambda_obs"
    const val DEFAULT_SLM_LAMBDA_OBS = 0.50f
    const val KEY_SLM_GAMMA_USER = "slm_gamma_user"
    const val DEFAULT_SLM_GAMMA_USER = 3.00f
    const val KEY_STREAMING_VOICE_INPUT = "streaming_voice_input"
    const val KEY_DEBUG_DISPLAY = "debug_display"
    const val KEY_RERANK_ENGINE_TYPE = "rerank_engine_type"
    const val KEY_SLM_RERANK_ENABLED = "slm_rerank_enabled"
    const val KEY_SPECULATIVE_PREFETCH = "speculative_prefetch_enabled"

    fun isSlmRerankEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SLM_RERANK_ENABLED, true)) {
            return false
        }
        return getRerankEngineType(context) != RerankEngineType.NONE
    }

    fun setSlmRerankEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SLM_RERANK_ENABLED, enabled).apply()
    }

    fun getRerankEngineType(context: Context): RerankEngineType {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultType = RerankEngineType.LOCAL_SLM
        val saved = prefs.getString(KEY_RERANK_ENGINE_TYPE, defaultType.id) ?: defaultType.id
        return RerankEngineType.fromId(saved)
    }

    fun setRerankEngineType(context: Context, type: RerankEngineType) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RERANK_ENGINE_TYPE, type.id).apply()
    }

    // Speculative prefetch (ACTION_DOWN) is permanently enabled for 0ms physical touch-down latency; UI option deprecated
    fun isSpeculativePrefetchEnabled(context: Context? = null): Boolean = true

    enum class HeightPreset(val displayName: String, val scale: Float) {
        COMPACT("超小型 (80%)", 0.80f),
        SHORT("低め (90%)", 0.90f),
        NORMAL("標準 (100%)", 1.00f),
        TALL("高め (110%)", 1.10f),
        EXTRA_TALL("大型 (120%)", 1.20f);

        companion object {
            fun fromScale(scale: Float): HeightPreset {
                return entries.minByOrNull { kotlin.math.abs(it.scale - scale) } ?: NORMAL
            }
        }
    }

    fun getHeightScale(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_HEIGHT_SCALE, 1.00f)
    }

    fun setHeightScale(context: Context, scale: Float) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_HEIGHT_SCALE, scale).apply()
    }

    fun getCurrentPreset(context: Context): HeightPreset {
        return HeightPreset.fromScale(getHeightScale(context))
    }

    fun setPreset(context: Context, preset: HeightPreset) {
        setHeightScale(context, preset.scale)
    }

    fun isQwertyNumberRowEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_QWERTY_NUMBER_ROW, true)
    }

    fun setQwertyNumberRowEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_QWERTY_NUMBER_ROW, enabled).apply()
    }

    fun isQwertySwipeTypingEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_QWERTY_SWIPE_TYPING, true)
    }

    fun setQwertySwipeTypingEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_QWERTY_SWIPE_TYPING, enabled).apply()
    }

    fun isEnglishAutoSpaceEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENGLISH_AUTO_SPACE, true)
    }

    fun setEnglishAutoSpaceEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENGLISH_AUTO_SPACE, enabled).apply()
    }

    fun isEnglishAutoPeriodEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENGLISH_AUTO_PERIOD, true)
    }

    fun setEnglishAutoPeriodEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENGLISH_AUTO_PERIOD, enabled).apply()
    }

    fun isJapanesePeriodSuggestionEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_JAPANESE_PERIOD_SUGGESTION, true)
    }

    fun setJapanesePeriodSuggestionEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_JAPANESE_PERIOD_SUGGESTION, enabled).apply()
    }


    fun getSlmWeightAlpha(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_SLM_WEIGHT_ALPHA, DEFAULT_SLM_WEIGHT_ALPHA)
    }

    fun setSlmWeightAlpha(context: Context, alpha: Float) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_SLM_WEIGHT_ALPHA, alpha).apply()
    }

    fun getSlmLambdaObs(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_SLM_LAMBDA_OBS, DEFAULT_SLM_LAMBDA_OBS)
    }

    fun setSlmLambdaObs(context: Context, lambdaObs: Float) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_SLM_LAMBDA_OBS, lambdaObs).apply()
    }

    fun getSlmGammaUser(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_SLM_GAMMA_USER, DEFAULT_SLM_GAMMA_USER)
    }

    fun setSlmGammaUser(context: Context, gammaUser: Float) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_SLM_GAMMA_USER, gammaUser).apply()
    }

    fun isStreamingVoiceInputEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_STREAMING_VOICE_INPUT, true)
    }

    fun setStreamingVoiceInputEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_STREAMING_VOICE_INPUT, enabled).apply()
    }

    fun isDebugDisplayEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DEBUG_DISPLAY, false)
    }

    fun setDebugDisplayEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DEBUG_DISPLAY, enabled).apply()
    }

    fun getLastKeyboardMode(context: Context): JapaneseKanaUtils.KeyboardMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LAST_KEYBOARD_MODE, JapaneseKanaUtils.KeyboardMode.KANA.name)
        return try {
            if (raw == "QWERTY") JapaneseKanaUtils.KeyboardMode.QWERTY
            else JapaneseKanaUtils.KeyboardMode.KANA
        } catch (e: Exception) {
            JapaneseKanaUtils.KeyboardMode.KANA
        }
    }

    fun setLastKeyboardMode(context: Context, mode: JapaneseKanaUtils.KeyboardMode) {
        val persisted = when (mode) {
            JapaneseKanaUtils.KeyboardMode.QWERTY,
            JapaneseKanaUtils.KeyboardMode.QWERTY_SYMBOLS -> JapaneseKanaUtils.KeyboardMode.QWERTY.name
            else -> JapaneseKanaUtils.KeyboardMode.KANA.name
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_KEYBOARD_MODE, persisted).apply()
    }

    /**
     * Calculates the target total keyboard height in pixels, taking into account:
     * - Orientation (Portrait vs Landscape)
     * - User scale preference (0.80x to 1.20x)
     * - Screen height clamp safeguard (never exceeds 45% in portrait or 60% in landscape)
     */
    fun calculateKeyboardHeightPx(context: Context): Int {
        val resources = context.resources
        val config = resources.configuration
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        val density = resources.displayMetrics.density
        val screenHeightPx = resources.displayMetrics.heightPixels

        // Base height in dp (adjusted so that old 120% corresponds to 100%)
        val baseDp = if (isLandscape) 168f else 258f
        val userScale = getHeightScale(context)
        val calculatedPx = (baseDp * userScale * density).toInt()

        // Clamp to avoid taking up the whole screen
        val maxAllowedPx = if (isLandscape) {
            (screenHeightPx * 0.60f).toInt()
        } else {
            (screenHeightPx * 0.45f).toInt()
        }
        val minAllowedPx = (110f * density).toInt()

        return calculatedPx.coerceIn(minAllowedPx, maxAllowedPx)
    }

    /**
     * Calculates the compact header bar height in pixels.
     */
    fun calculateHeaderHeightPx(context: Context): Int {
        val resources = context.resources
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val density = resources.displayMetrics.density
        val baseDp = if (isLandscape) 30f else 36f
        return (baseDp * density).toInt()
    }

    /**
     * Calculates the debug context bar height in pixels.
     */
    fun calculateContextBarHeightPx(context: Context): Int {
        if (!isDebugDisplayEnabled(context)) return 0
        val resources = context.resources
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val density = resources.displayMetrics.density
        val baseDp = if (isLandscape) 16f else 20f
        return (baseDp * density).toInt()
    }
}
