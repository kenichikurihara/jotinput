package com.example.myasr.context.speech.infrastructure

import android.content.Context
import android.util.Log
import com.example.myasr.context.speech.domain.AsrLanguage
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

class AsrManager(private val context: Context) {
    private val TAG = "AsrManager"
    private var japaneseRecognizer: OfflineRecognizer? = null
    private var englishRecognizer: OfflineRecognizer? = null

    companion object {
        // Japanese ReazonSpeech (Zipformer Transducer)
        const val MODEL_DIR_NAME = "reazonspeech"
        const val ENCODER_INT8 = "encoder-epoch-99-avg-1.int8.onnx"
        const val ENCODER_FP32 = "encoder-epoch-99-avg-1.onnx"
        const val DECODER = "decoder-epoch-99-avg-1.onnx"
        const val JOINER_INT8 = "joiner-epoch-99-avg-1.int8.onnx"
        const val JOINER_FP32 = "joiner-epoch-99-avg-1.onnx"
        const val TOKENS = "tokens.txt"

        // English NVIDIA Parakeet TDT-CTC 110M (FastConformer NeMo CTC, CC-BY-4.0)
        const val ENGLISH_MODEL_DIR_NAME = "parakeet-en-110m"
        const val ENGLISH_MODEL_INT8 = "model.int8.onnx"
        const val ENGLISH_MODEL_FP32 = "model.onnx"
        const val ENGLISH_TOKENS = "tokens.txt"

        /**
         * Determines optimal CPU inference threads for ONNX Runtime based on available physical cores.
         * Keeps between 2 and 4 to balance peak throughput and avoid LITTLE-core cache thrashing.
         */
        fun getOptimalThreadCount(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return (cores / 2).coerceIn(2, 4)
        }
    }

    fun isModelAvailable(language: AsrLanguage = AsrLanguage.JAPANESE): Boolean {
        return when (language) {
            AsrLanguage.JAPANESE -> isModelInAssets() || isModelInStorage()
            AsrLanguage.ENGLISH -> isEnglishModelInStorage()
        }
    }

    fun isEnglishModelAvailable(): Boolean = isModelAvailable(AsrLanguage.ENGLISH)

    fun isModelInAssets(): Boolean {
        return try {
            val list = context.assets.list(MODEL_DIR_NAME) ?: emptyArray()
            val hasTokens = list.contains(TOKENS)
            val hasEncoder = list.contains(ENCODER_INT8) || list.contains(ENCODER_FP32)
            val hasDecoder = list.contains(DECODER)
            val hasJoiner = list.contains(JOINER_INT8) || list.contains(JOINER_FP32)
            hasTokens && hasEncoder && hasDecoder && hasJoiner
        } catch (e: Exception) {
            false
        }
    }

    fun getStorageModelDir(language: AsrLanguage = AsrLanguage.JAPANESE): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dirName = when (language) {
            AsrLanguage.JAPANESE -> MODEL_DIR_NAME
            AsrLanguage.ENGLISH -> ENGLISH_MODEL_DIR_NAME
        }
        return File(baseDir, dirName)
    }

    fun getEnglishStorageModelDir(): File = getStorageModelDir(AsrLanguage.ENGLISH)

    fun isModelInStorage(): Boolean {
        val dir = getStorageModelDir(AsrLanguage.JAPANESE)
        if (!dir.exists() || !dir.isDirectory) return false

        val tokens = File(dir, TOKENS).exists()
        val encoder = File(dir, ENCODER_INT8).exists() || File(dir, ENCODER_FP32).exists()
        val decoder = File(dir, DECODER).exists()
        val joiner = File(dir, JOINER_INT8).exists() || File(dir, JOINER_FP32).exists()
        return tokens && encoder && decoder && joiner
    }

    fun isEnglishModelInStorage(): Boolean {
        val dir = getEnglishStorageModelDir()
        if (!dir.exists() || !dir.isDirectory) return false

        val tokens = File(dir, ENGLISH_TOKENS).exists()
        val model = File(dir, ENGLISH_MODEL_INT8).exists() || File(dir, ENGLISH_MODEL_FP32).exists()
        return tokens && model
    }

    @Synchronized
    fun initialize(hotwordsFile: File? = null, language: AsrLanguage = AsrLanguage.JAPANESE): Boolean {
        return when (language) {
            AsrLanguage.JAPANESE -> initializeJapanese(hotwordsFile)
            AsrLanguage.ENGLISH -> initializeEnglish()
        }
    }

    private fun initializeJapanese(hotwordsFile: File?): Boolean {
        if (japaneseRecognizer != null) return true

        return try {
            if (isModelInAssets()) {
                Log.i(TAG, "Initializing ReazonSpeech model from Android assets (modified_beam_search)...")
                val list = context.assets.list(MODEL_DIR_NAME)?.toSet() ?: emptySet()
                val encoderName = if (list.contains(ENCODER_INT8)) ENCODER_INT8 else ENCODER_FP32
                val joinerName = if (list.contains(JOINER_INT8)) JOINER_INT8 else JOINER_FP32

                val modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = "$MODEL_DIR_NAME/$encoderName",
                        decoder = "$MODEL_DIR_NAME/$DECODER",
                        joiner = "$MODEL_DIR_NAME/$joinerName"
                    ),
                    tokens = "$MODEL_DIR_NAME/$TOKENS",
                    modelType = "transducer",
                    modelingUnit = "cjkchar",
                    numThreads = getOptimalThreadCount(),
                    provider = "cpu"
                )
                // Note: when assetManager != null, hotwordsFile must not be a filesystem path.
                // We enable "modified_beam_search" here so per-stream hotwords can be passed dynamically.
                val config = OfflineRecognizerConfig(
                    modelConfig = modelConfig,
                    decodingMethod = "modified_beam_search",
                    maxActivePaths = 8,
                    hotwordsFile = "",
                    hotwordsScore = 3.5f
                )
                japaneseRecognizer = OfflineRecognizer(context.assets, config)
                Log.i(TAG, "ReazonSpeech model successfully loaded from assets with modified_beam_search")
                true
            } else if (isModelInStorage()) {
                val dir = getStorageModelDir(AsrLanguage.JAPANESE)
                val effectiveHotwordsFile = hotwordsFile
                    ?: File(context.filesDir, "hotwords.txt").takeIf { it.exists() && it.length() > 0 }
                val hasHotwords = effectiveHotwordsFile != null && effectiveHotwordsFile.exists() && effectiveHotwordsFile.length() > 0
                val hotwordsPath = if (hasHotwords) effectiveHotwordsFile!!.absolutePath else ""
                Log.i(TAG, "Initializing ReazonSpeech model from storage: ${dir.absolutePath} (hotwords=$hotwordsPath)")
                val encoderFile = if (File(dir, ENCODER_INT8).exists()) File(dir, ENCODER_INT8) else File(dir, ENCODER_FP32)
                val joinerFile = if (File(dir, JOINER_INT8).exists()) File(dir, JOINER_INT8) else File(dir, JOINER_FP32)
                val decoderFile = File(dir, DECODER)
                val tokensFile = File(dir, TOKENS)

                val modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = encoderFile.absolutePath,
                        decoder = decoderFile.absolutePath,
                        joiner = joinerFile.absolutePath
                    ),
                    tokens = tokensFile.absolutePath,
                    modelType = "transducer",
                    modelingUnit = "cjkchar",
                    numThreads = getOptimalThreadCount(),
                    provider = "cpu"
                )
                val config = OfflineRecognizerConfig(
                    modelConfig = modelConfig,
                    decodingMethod = "modified_beam_search",
                    maxActivePaths = 8,
                    hotwordsFile = hotwordsPath,
                    hotwordsScore = 3.5f
                )
                japaneseRecognizer = OfflineRecognizer(null, config)
                Log.i(TAG, "ReazonSpeech model successfully loaded from storage with hotwords: $hasHotwords")
                true
            } else {
                Log.w(TAG, "No ReazonSpeech model files found in assets or storage")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Japanese OfflineRecognizer: ${e.message}", e)
            japaneseRecognizer = null
            false
        }
    }

    private fun initializeEnglish(): Boolean {
        if (englishRecognizer != null) return true

        return try {
            if (!isEnglishModelInStorage()) {
                Log.w(TAG, "No English Parakeet model files found in storage")
                return false
            }
            val dir = getEnglishStorageModelDir()
            val modelFile = if (File(dir, ENGLISH_MODEL_INT8).exists()) {
                File(dir, ENGLISH_MODEL_INT8)
            } else {
                File(dir, ENGLISH_MODEL_FP32)
            }
            val tokensFile = File(dir, ENGLISH_TOKENS)
            Log.i(TAG, "Initializing English Parakeet TDT-CTC 110M from storage: ${dir.absolutePath}")

            val modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = modelFile.absolutePath
                ),
                tokens = tokensFile.absolutePath,
                numThreads = getOptimalThreadCount(),
                provider = "cpu"
            )
            val config = OfflineRecognizerConfig(
                modelConfig = modelConfig,
                decodingMethod = "greedy_search"
            )
            englishRecognizer = OfflineRecognizer(null, config)
            Log.i(TAG, "English Parakeet TDT-CTC 110M model successfully loaded")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize English OfflineRecognizer: ${e.message}", e)
            englishRecognizer = null
            false
        }
    }

    @Volatile
    private var lastJapaneseHotwordsHash: Int? = null

    fun isInitialized(language: AsrLanguage = AsrLanguage.JAPANESE): Boolean {
        return when (language) {
            AsrLanguage.JAPANESE -> japaneseRecognizer != null
            AsrLanguage.ENGLISH -> englishRecognizer != null
        }
    }

    @Synchronized
    fun reload(hotwordsFile: File? = null, language: AsrLanguage = AsrLanguage.JAPANESE): Boolean {
        if (language == AsrLanguage.JAPANESE && japaneseRecognizer != null) {
            val newHash = try {
                if (hotwordsFile != null && hotwordsFile.exists()) hotwordsFile.readText(Charsets.UTF_8).hashCode() else 0
            } catch (_: Exception) {
                null
            }
            if (newHash != null && newHash == lastJapaneseHotwordsHash) {
                Log.d(TAG, "Skipping redundant Japanese ASR reload (hotwords unchanged).")
                return true
            }
            lastJapaneseHotwordsHash = newHash
        }
        release(language)
        return initialize(hotwordsFile, language)
    }

    /**
     * Non-blocking or background warm-up: ensures model is loaded into memory
     * and performs a micro inference pass (0.1s silence) to prime ONNX Runtime
     * memory allocators, thread pools, and decoder graphs.
     */
    @Synchronized
    fun warmUp(hotwordsFile: File? = null, language: AsrLanguage = AsrLanguage.JAPANESE): Boolean {
        if (!isModelAvailable(language)) {
            Log.d(TAG, "AsrManager warmUp ($language) skipped: model files not available yet")
            return false
        }
        val ok = initialize(hotwordsFile, language)
        if (!ok) return false
        return try {
            val dummyAudio = FloatArray(1600) // 0.1s silence at 16kHz
            transcribe(dummyAudio, 16000, "", language)
            Log.i(TAG, "AsrManager warm-up ($language) inference completed successfully.")
            true
        } catch (e: Exception) {
            Log.w(TAG, "AsrManager warm-up ($language) inference failed: ${e.message}")
            false
        }
    }

    @Synchronized
    fun transcribe(
        samples: FloatArray,
        sampleRate: Int = 16000,
        hotwords: String = "",
        language: AsrLanguage = AsrLanguage.JAPANESE
    ): String {
        val rec = when (language) {
            AsrLanguage.JAPANESE -> japaneseRecognizer
            AsrLanguage.ENGLISH -> englishRecognizer
        } ?: throw IllegalStateException("ASR model ($language) is not initialized")

        if (samples.isEmpty()) return ""

        val stream = if (language == AsrLanguage.JAPANESE && hotwords.isNotBlank()) {
            try {
                Log.d(TAG, "Creating OfflineStream with dynamic hotwords (${hotwords.length} chars)")
                rec.createStream(hotwords)
            } catch (e: Exception) {
                Log.w(TAG, "createStream with hotwords failed (${e.message}), falling back to default stream")
                rec.createStream()
            }
        } else {
            rec.createStream()
        }

        return try {
            stream.acceptWaveform(samples, sampleRate)
            rec.decode(stream)
            val result = rec.getResult(stream)
            result.text.trim()
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun release(language: AsrLanguage? = null) {
        when (language) {
            AsrLanguage.JAPANESE -> {
                japaneseRecognizer?.release()
                japaneseRecognizer = null
            }
            AsrLanguage.ENGLISH -> {
                englishRecognizer?.release()
                englishRecognizer = null
            }
            null -> {
                japaneseRecognizer?.release()
                japaneseRecognizer = null
                englishRecognizer?.release()
                englishRecognizer = null
            }
        }
    }
}
