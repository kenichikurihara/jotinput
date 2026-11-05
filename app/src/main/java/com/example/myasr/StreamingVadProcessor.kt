package com.example.myasr

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Energy-based streaming Voice Activity Detector (VAD) and continuous speech chunker.
 * Automatically segments live PCM audio streams at natural pauses (silence),
 * delivering speech chunks asynchronously while keeping the microphone stream alive.
 *
 * Includes dynamic noise floor tracking to reliably detect pauses across diverse
 * real-world mobile acoustic environments (Pixel 10 Pro, ARCVM, ambient room noise, AGC).
 */
class StreamingVadProcessor(
    val sampleRate: Int = 16000,
    var speechThreshold: Float = 0.024f,
    var silenceThreshold: Float = 0.018f,
    var silenceDurationMs: Long = 450L,
    var microPauseDurationMs: Long = 200L,
    var minSentenceDurationMs: Long = 800L,
    var sentencePacingDurationMs: Long = 6000L,
    var minSpeechDurationMs: Long = 180L,
    var maxSpeechDurationMs: Long = 12000L,
    var preRollDurationMs: Long = 250L,
    var onSpeechChunkCaptured: ((FloatArray) -> Unit)? = null,
    var onSpeechStateChanged: ((isSpeaking: Boolean) -> Unit)? = null,
    var onStreamingAudioSamples: ((ShortArray, Int) -> Unit)? = null,
    var isStreamingMode: Boolean = false
) {
    private val TAG = "StreamingVad"

    enum class State {
        SILENCE,
        SPEECH
    }

    @Volatile
    var state: State = State.SILENCE
        private set

    @Volatile
    var noiseFloor: Float = 0.015f
        private set

    private var totalChunksProcessed = 0

    // Sliding window of recent chunk amplitudes (~1.5s) to continuously track ambient floor
    private val windowCapacity = 24
    private val recentAmps = FloatArray(windowCapacity)
    private var recentAmpsCount = 0
    private var recentAmpsIndex = 0

    private val preRollSampleCount = (sampleRate * preRollDurationMs / 1000).toInt()
    private val preRollBuffer = ShortArray(preRollSampleCount)
    private var preRollIndex = 0
    private var preRollFilled = false

    private val speechStream = ByteArrayOutputStream()
    private var speechSampleCount = 0
    private var silenceSampleCount = 0
    private var consecutiveSpeechChunks = 0
    private var sessionChunksDelivered = 0

    @Volatile
    private var typingSuppressionEndTimeMs: Long = 0L

    /**
     * Resets internal state for a new recording session while retaining calibrated noise floor.
     */
    @Synchronized
    fun reset() {
        transitionToSilence()
        preRollIndex = 0
        preRollFilled = false
        totalChunksProcessed = 0
        recentAmpsCount = 0
        recentAmpsIndex = 0
    }

    /**
     * Notifies the VAD that the user is currently typing (keystroke or flick touch).
     * Temporarily suppresses starting new speech detection to prevent typing acoustic
     * clicks and vibration noise from triggering false speech starts.
     */
    fun notifyUserTyping(durationMs: Long = 300L) {
        val targetTime = System.currentTimeMillis() + durationMs
        if (targetTime > typingSuppressionEndTimeMs) {
            typingSuppressionEndTimeMs = targetTime
        }
    }

    fun isTypingSuppressed(): Boolean {
        return System.currentTimeMillis() < typingSuppressionEndTimeMs
    }

    /**
     * Processes a chunk of incoming 16-bit PCM audio samples.
     * @param buffer Raw 16-bit PCM short samples.
     * @param count Number of valid samples in the buffer.
     * @return Average normalized amplitude (0.0 to 1.0) of this chunk.
     */
    @Synchronized
    fun processSamples(buffer: ShortArray, count: Int): Float {
        if (count <= 0) return 0f

        var sumAmp = 0.0
        for (i in 0 until count) {
            sumAmp += Math.abs(buffer[i].toInt())
        }
        val avgAmp = ((sumAmp / count) / 32768.0).toFloat().coerceIn(0f, 1f)
        val now = System.currentTimeMillis()
        val isSuppressed = now < typingSuppressionEndTimeMs

        // Fast initial calibration on first chunk of session
        if (totalChunksProcessed == 0 && !isSuppressed) {
            noiseFloor = minOf(avgAmp, 0.030f).coerceAtLeast(0.005f)
        }
        totalChunksProcessed++

        // Update sliding window of recent chunk amplitudes
        recentAmps[recentAmpsIndex] = avgAmp
        recentAmpsIndex = (recentAmpsIndex + 1) % windowCapacity
        if (recentAmpsCount < windowCapacity) recentAmpsCount++

        // Continuously adapt noise floor from the minimum energy in the recent sliding window.
        // In any ~1.5s window, natural speech is intermittent (closures, micro-pauses), so the
        // window minimum reliably tracks true background noise across AGC gain shifts.
        if (recentAmpsCount >= 4 && !isSuppressed) {
            var minAmp = Float.MAX_VALUE
            for (i in 0 until recentAmpsCount) {
                if (recentAmps[i] < minAmp) {
                    minAmp = recentAmps[i]
                }
            }
            val targetFloor = minAmp.coerceIn(0.004f, 0.035f)
            noiseFloor = if (targetFloor < noiseFloor) {
                maxOf(0.003f, noiseFloor * 0.70f + targetFloor * 0.30f)
            } else {
                minOf(0.035f, noiseFloor * 0.94f + targetFloor * 0.06f)
            }
        }

        // Dynamically compute effective silence and speech thresholds based on ambient noise floor
        val effectiveSilenceThreshold = maxOf(silenceThreshold, noiseFloor + 0.005f)
        val effectiveSpeechThreshold = maxOf(speechThreshold, effectiveSilenceThreshold + 0.005f)

        // Deliver audio samples to streaming listener regardless of speech state if in streaming mode
        onStreamingAudioSamples?.invoke(buffer, count)

        when (state) {
            State.SILENCE -> {
                // Update ring pre-roll buffer
                for (i in 0 until count) {
                    preRollBuffer[preRollIndex] = buffer[i]
                    preRollIndex = (preRollIndex + 1) % preRollSampleCount
                    if (preRollIndex == 0) {
                        preRollFilled = true
                    }
                }

                // Check for speech onset with adaptive noise floor headroom
                if (!isSuppressed && avgAmp >= effectiveSpeechThreshold) {
                    consecutiveSpeechChunks++
                    // Require at least 2 consecutive chunks (~128ms) to avoid single-click impulse noise
                    if (consecutiveSpeechChunks >= 2) {
                        Log.d(TAG, "Speech onset detected: avgAmp=$avgAmp >= threshold=$effectiveSpeechThreshold (noiseFloor=$noiseFloor)")
                        transitionToSpeech()
                        appendSamplesToSpeech(buffer, count)
                    }
                } else {
                    consecutiveSpeechChunks = 0
                }
            }

            State.SPEECH -> {
                appendSamplesToSpeech(buffer, count)

                val currentSpeechDurationMs = (speechSampleCount * 1000L) / sampleRate

                if (avgAmp < effectiveSilenceThreshold) {
                    silenceSampleCount += count
                    val currentSilenceMs = (silenceSampleCount * 1000L) / sampleRate

                    if (isStreamingMode) {
                        // In streaming mode: pause-based chunking is completely bypassed.
                        // We do not physically chunk the audio on pauses; speech stream flows continuously.
                        // Only change speaking indicator state if silence persists (> 1000ms).
                        if (currentSilenceMs >= 1000L) {
                            onSpeechStateChanged?.invoke(false)
                        }
                    } else {
                        // Condition 1: Micro-pause at sentence boundary (speech >= 800ms + silence >= 200ms)
                        if (currentSpeechDurationMs >= minSentenceDurationMs && currentSilenceMs >= microPauseDurationMs) {
                            Log.d(TAG, "Micro-pause detected: speech=${currentSpeechDurationMs}ms, silence=${currentSilenceMs}ms")
                            flushSpeechChunk()
                            transitionToSilence()
                        }
                        // Condition 2: Standard conversational pause (silence >= 450ms)
                        else if (currentSilenceMs >= silenceDurationMs) {
                            Log.d(TAG, "Standard pause detected: speech=${currentSpeechDurationMs}ms, silence=${currentSilenceMs}ms")
                            flushSpeechChunk()
                            transitionToSilence()
                        }
                    }
                } else {
                    if (isStreamingMode && silenceSampleCount > 0) {
                        onSpeechStateChanged?.invoke(true)
                    }
                    silenceSampleCount = 0

                    if (!isStreamingMode) {
                        // Condition 3: Safety continuous pacing for runaway long utterances
                        if (currentSpeechDurationMs >= sentencePacingDurationMs) {
                            Log.d(TAG, "Safety continuous pacing trigger: duration=${currentSpeechDurationMs}ms")
                            flushSpeechChunk()
                            if (avgAmp < effectiveSilenceThreshold) {
                                transitionToSilence()
                            } else {
                                speechStream.reset()
                                speechSampleCount = 0
                                silenceSampleCount = 0
                            }
                        }
                    }
                }

                // Guard against excessively long uninterrupted utterances (safety only)
                if (!isStreamingMode && currentSpeechDurationMs >= maxSpeechDurationMs) {
                    Log.d(TAG, "Max speech duration trigger: duration=${currentSpeechDurationMs}ms")
                    flushSpeechChunk()
                    if (avgAmp < effectiveSilenceThreshold) {
                        transitionToSilence()
                    } else {
                        speechStream.reset()
                        speechSampleCount = 0
                        silenceSampleCount = 0
                    }
                }
            }
        }

        return avgAmp
    }

    private fun transitionToSpeech() {
        state = State.SPEECH
        speechStream.reset()
        speechSampleCount = 0
        silenceSampleCount = 0
        consecutiveSpeechChunks = 0
        sessionChunksDelivered = 0

        // Flush pre-roll ring buffer into speech stream
        val byteBuffer = ByteBuffer.allocate(preRollSampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        if (preRollFilled) {
            // Write older part: [preRollIndex .. preRollSampleCount)
            for (i in preRollIndex until preRollSampleCount) {
                byteBuffer.putShort(preRollBuffer[i])
            }
            // Write newer part: [0 .. preRollIndex)
            for (i in 0 until preRollIndex) {
                byteBuffer.putShort(preRollBuffer[i])
            }
            speechStream.write(byteBuffer.array(), 0, preRollSampleCount * 2)
            speechSampleCount += preRollSampleCount
        } else if (preRollIndex > 0) {
            for (i in 0 until preRollIndex) {
                byteBuffer.putShort(preRollBuffer[i])
            }
            speechStream.write(byteBuffer.array(), 0, preRollIndex * 2)
            speechSampleCount += preRollIndex
        }

        onSpeechStateChanged?.invoke(true)
    }

    private fun transitionToSilence() {
        state = State.SILENCE
        speechStream.reset()
        speechSampleCount = 0
        silenceSampleCount = 0
        consecutiveSpeechChunks = 0
        sessionChunksDelivered = 0
        onSpeechStateChanged?.invoke(false)
    }

    private fun appendSamplesToSpeech(buffer: ShortArray, count: Int) {
        val byteBuffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            byteBuffer.putShort(buffer[i])
        }
        speechStream.write(byteBuffer.array(), 0, count * 2)
        speechSampleCount += count
    }

    private fun flushSpeechChunk() {
        val durationMs = (speechSampleCount * 1000L) / sampleRate
        // If this is the first chunk, ensure it is not a momentary acoustic click (< minSpeechDurationMs).
        // If a chunk was already emitted in this session, never discard the tail of speech.
        if (sessionChunksDelivered == 0 && durationMs < minSpeechDurationMs) {
            return
        }

        val pcmBytes = speechStream.toByteArray()
        val numSamples = pcmBytes.size / 2
        if (numSamples <= 0) return

        val floatSamples = FloatArray(numSamples)
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until numSamples) {
            floatSamples[i] = shortBuffer.get(i) / 32768.0f
        }

        sessionChunksDelivered++
        onSpeechChunkCaptured?.invoke(floatSamples)
    }

    /**
     * Returns true if the VAD is currently in SPEECH state with enough accumulated speech samples.
     */
    @Synchronized
    fun hasUnflushedSpeech(): Boolean {
        val durationMs = (speechSampleCount * 1000L) / sampleRate
        return state == State.SPEECH && (sessionChunksDelivered > 0 || durationMs >= minSpeechDurationMs)
    }

    /**
     * Discards any currently accumulated speech and resets to SILENCE without emitting a chunk.
     * Used when confirming/resetting a streaming utterance or cancelling speech on backspace.
     */
    @Synchronized
    fun discardCurrentSpeech() {
        transitionToSilence()
        preRollIndex = 0
        preRollFilled = false
    }

    /**
     * Manually flushes any pending speech when stopping or force-finishing.
     */
    @Synchronized
    fun flushAndReset(): FloatArray? {
        val durationMs = (speechSampleCount * 1000L) / sampleRate
        var result: FloatArray? = null
        if (state == State.SPEECH && (sessionChunksDelivered > 0 || durationMs >= minSpeechDurationMs)) {
            val pcmBytes = speechStream.toByteArray()
            val numSamples = pcmBytes.size / 2
            if (numSamples > 0) {
                val floatSamples = FloatArray(numSamples)
                val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (i in 0 until numSamples) {
                    floatSamples[i] = shortBuffer.get(i) / 32768.0f
                }
                result = floatSamples
            }
        }
        transitionToSilence()
        preRollIndex = 0
        preRollFilled = false
        return result
    }
}
