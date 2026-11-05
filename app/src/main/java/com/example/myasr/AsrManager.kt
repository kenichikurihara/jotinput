package com.example.myasr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

class AsrManager(private val context: Context) {
    private val TAG = "AsrManager"
    private var recognizer: OfflineRecognizer? = null

    companion object {
        const val MODEL_DIR_NAME = "reazonspeech"
        const val ENCODER_INT8 = "encoder-epoch-99-avg-1.int8.onnx"
        const val ENCODER_FP32 = "encoder-epoch-99-avg-1.onnx"
        const val DECODER = "decoder-epoch-99-avg-1.onnx"
        const val JOINER_INT8 = "joiner-epoch-99-avg-1.int8.onnx"
        const val JOINER_FP32 = "joiner-epoch-99-avg-1.onnx"
        const val TOKENS = "tokens.txt"

        /**
         * Determines optimal CPU inference threads for ONNX Runtime based on available physical cores.
         * Keeps between 2 and 4 to balance peak throughput and avoid LITTLE-core cache thrashing.
         */
        fun getOptimalThreadCount(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return (cores / 2).coerceIn(2, 4)
        }
    }

    fun isModelAvailable(): Boolean {
        return isModelInAssets() || isModelInStorage()
    }

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

    fun getStorageModelDir(): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(baseDir, MODEL_DIR_NAME)
    }

    fun isModelInStorage(): Boolean {
        val dir = getStorageModelDir()
        if (!dir.exists() || !dir.isDirectory) return false

        val tokens = File(dir, TOKENS).exists()
        val encoder = File(dir, ENCODER_INT8).exists() || File(dir, ENCODER_FP32).exists()
        val decoder = File(dir, DECODER).exists()
        val joiner = File(dir, JOINER_INT8).exists() || File(dir, JOINER_FP32).exists()
        return tokens && encoder && decoder && joiner
    }

    @Synchronized
    fun initialize(hotwordsFile: File? = null): Boolean {
        if (recognizer != null) return true

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
                recognizer = OfflineRecognizer(context.assets, config)
                Log.i(TAG, "ReazonSpeech model successfully loaded from assets with modified_beam_search")
                true
            } else if (isModelInStorage()) {
                val dir = getStorageModelDir()
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
                recognizer = OfflineRecognizer(null, config)
                Log.i(TAG, "ReazonSpeech model successfully loaded from storage with hotwords: $hasHotwords")
                true
            } else {
                Log.w(TAG, "No ReazonSpeech model files found in assets or storage")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OfflineRecognizer: ${e.message}", e)
            recognizer = null
            false
        }
    }

    @Synchronized
    fun reload(hotwordsFile: File? = null): Boolean {
        release()
        return initialize(hotwordsFile)
    }

    /**
     * Non-blocking or background warm-up: ensures model is loaded into memory
     * and performs a micro inference pass (0.1s silence) to prime ONNX Runtime
     * memory allocators, thread pools, and transducer decoder graphs.
     */
    @Synchronized
    fun warmUp(hotwordsFile: File? = null): Boolean {
        if (!isModelAvailable()) {
            Log.d(TAG, "AsrManager warmUp skipped: model files not available yet")
            return false
        }
        val ok = initialize(hotwordsFile)
        if (!ok) return false
        return try {
            val dummyAudio = FloatArray(1600) // 0.1s silence at 16kHz
            transcribe(dummyAudio, 16000)
            Log.i(TAG, "AsrManager warm-up inference completed successfully.")
            true
        } catch (e: Exception) {
            Log.w(TAG, "AsrManager warm-up inference failed: ${e.message}")
            false
        }
    }

    @Synchronized
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000, hotwords: String = ""): String {
        val rec = recognizer ?: throw IllegalStateException("ASR model is not initialized")
        if (samples.isEmpty()) return ""

        val stream = if (hotwords.isNotBlank()) {
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

    fun release() {
        recognizer?.release()
        recognizer = null
    }
}
