package com.example.myasr.context.speech.infrastructure

import com.example.myasr.MainActivity
import com.example.myasr.context.speech.domain.StreamingVadProcessor

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder(
    private val sampleRate: Int = 16000,
    var onAudioLevel: ((Float) -> Unit)? = null,
    var onSpeechChunkCaptured: ((FloatArray) -> Unit)? = null,
    var onSpeechStateChanged: ((isSpeaking: Boolean) -> Unit)? = null,
    var onStreamingAudioCaptured: ((ShortArray, Int) -> Unit)? = null
) {
    private val TAG = "AudioRecorder"
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val recordedPcmData = ByteArrayOutputStream()

    var isStreamingMode: Boolean
        get() = vadProcessor.isStreamingMode
        set(value) {
            vadProcessor.isStreamingMode = value
        }

    @Volatile
    var capturedChunksCount = 0
        private set

    val vadProcessor = StreamingVadProcessor(
        sampleRate = sampleRate,
        speechThreshold = 0.024f,
        silenceThreshold = 0.018f,
        silenceDurationMs = 450L,
        microPauseDurationMs = 200L,
        minSentenceDurationMs = 800L,
        sentencePacingDurationMs = 6000L,
        minSpeechDurationMs = 180L,
        maxSpeechDurationMs = 12000L,
        preRollDurationMs = 250L,
        onSpeechChunkCaptured = { chunk ->
            capturedChunksCount++
            Log.d(TAG, "Speech chunk #$capturedChunksCount captured by VAD (${chunk.size} samples, ${chunk.size / sampleRate.toFloat()}s)")
            synchronized(recordedPcmData) {
                recordedPcmData.reset()
            }
            onSpeechChunkCaptured?.invoke(chunk)
        },
        onSpeechStateChanged = { isSpeaking ->
            Log.d(TAG, "VAD speech state changed: isSpeaking=$isSpeaking")
            onSpeechStateChanged?.invoke(isSpeaking)
        }
    )

    /**
     * Informs the VAD processor that the user just touched or typed on the keyboard.
     * Prevents finger taps from triggering false speech starts during conversational pauses.
     */
    fun notifyUserTyping(durationMs: Long = 300L) {
        vadProcessor.notifyUserTyping(durationMs)
    }

    /**
     * Feeds incoming PCM audio samples into the VAD processor and raw data accumulator.
     * Exposed to facilitate deterministic testing without requiring Android mic hardware.
     */
    fun feedAudioData(buffer: ShortArray, readSamples: Int) {
        if (readSamples <= 0) return
        val avgAmp = vadProcessor.processSamples(buffer, readSamples)
        onAudioLevel?.invoke(avgAmp)
        onStreamingAudioCaptured?.invoke(buffer, readSamples)

        val byteBuffer = ByteBuffer.allocate(readSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until readSamples) {
            byteBuffer.putShort(buffer[i])
        }
        synchronized(recordedPcmData) {
            recordedPcmData.write(byteBuffer.array(), 0, readSamples * 2)
            // In streaming mode, prevent unbounded silence accumulation before speech onset
            if (isStreamingMode && vadProcessor.state == StreamingVadProcessor.State.SILENCE && recordedPcmData.size() > sampleRate * 2) {
                trimAccumulatedAudio(0.5f)
            }
        }
    }

    /**
     * Returns true if the user is currently mid-speech with unflushed audio samples.
     */
    fun hasUnflushedSpeech(): Boolean {
        return vadProcessor.hasUnflushedSpeech()
    }

    /**
     * Immediately flushes an in-flight speech chunk in non-streaming VAD mode
     * (e.g., when the user starts typing on the keyboard or presses Confirm before the silence timeout).
     */
    fun flushPendingSpeechChunk(invokeCallback: Boolean = true): FloatArray? {
        if (isStreamingMode) return null
        val chunk = vadProcessor.flushAndReset()
        if (chunk != null && chunk.isNotEmpty()) {
            capturedChunksCount++
            synchronized(recordedPcmData) {
                recordedPcmData.reset()
            }
            if (invokeCallback) {
                onSpeechChunkCaptured?.invoke(chunk)
            }
            return chunk
        }
        return null
    }

    /**
     * Resets the current streaming utterance buffer after the active streaming text has been
     * confirmed/committed via keyboard interaction (Enter/Confirm, Flick input, Space, Delete, etc.),
     * ensuring the already-committed speech is never re-decoded or duplicated.
     */
    fun resetStreamingUtterance() {
        capturedChunksCount++
        vadProcessor.discardCurrentSpeech()
        synchronized(recordedPcmData) {
            recordedPcmData.reset()
        }
    }

    /**
     * Discards any uncommitted in-flight speech audio without emitting a chunk
     * (e.g., when the user presses Backspace to cancel/delete while speaking).
     */
    fun discardCurrentSpeech() {
        vadProcessor.discardCurrentSpeech()
        synchronized(recordedPcmData) {
            recordedPcmData.reset()
        }
    }

    /**
     * Obtains a snapshot of all accumulated audio samples in the current recording session
     * as normalized FloatArray (-1.0 to 1.0) without resetting the underlying buffer.
     */
    fun getSnapshotSamples(): FloatArray {
        val pcmBytes: ByteArray
        synchronized(recordedPcmData) {
            pcmBytes = recordedPcmData.toByteArray()
        }
        val numSamples = pcmBytes.size / 2
        if (numSamples <= 0) return FloatArray(0)

        val floatSamples = FloatArray(numSamples)
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until numSamples) {
            floatSamples[i] = shortBuffer.get(i) / 32768.0f
        }
        return floatSamples
    }

    /**
     * Resets or trims the accumulated PCM data buffer, typically called when a stabilized
     * sentence prefix has been permanently committed to prevent memory growth.
     */
    fun trimAccumulatedAudio(keepLastSeconds: Float = 0.5f) {
        synchronized(recordedPcmData) {
            val pcmBytes = recordedPcmData.toByteArray()
            val keepBytes = (keepLastSeconds * sampleRate * 2).toInt().coerceAtMost(pcmBytes.size)
            recordedPcmData.reset()
            if (keepBytes > 0) {
                recordedPcmData.write(pcmBytes, pcmBytes.size - keepBytes, keepBytes)
            }
        }
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRecording.get()) return true
        capturedChunksCount = 0
        vadProcessor.reset()

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = minBufferSize.coerceAtLeast(sampleRate * 2) // 1 second buffer

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            recordedPcmData.reset()
            audioRecord?.startRecording()
            isRecording.set(true)

            recordingThread = Thread {
                val buffer = ShortArray(1024)

                while (isRecording.get()) {
                    val readSamples = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSamples > 0) {
                        feedAudioData(buffer, readSamples)
                    } else if (readSamples < 0) {
                        Log.w(TAG, "AudioRecord read error code: $readSamples")
                        try {
                            Thread.sleep(20)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }.apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording: ${e.message}", e)
            stop()
            return false
        }
    }

    @Synchronized
    fun stop(): FloatArray {
        isRecording.set(false)
        try {
            audioRecord?.stop()
            recordingThread?.join(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        } finally {
            audioRecord?.release()
            audioRecord = null
            recordingThread = null
        }

        // 1. Check if there is an in-flight speech chunk in the VAD (user was speaking when stopping)
        val inFlightChunk = vadProcessor.flushAndReset()
        if (inFlightChunk != null && inFlightChunk.isNotEmpty()) {
            synchronized(recordedPcmData) {
                recordedPcmData.reset()
            }
            return inFlightChunk
        }

        // 2. If one or more speech chunks were already captured and delivered via callback
        // during this recording session, do NOT return previously emitted residual audio!
        // This guarantees that pausing and subsequent stop will never repeat the same utterance.
        if (capturedChunksCount > 0) {
            synchronized(recordedPcmData) {
                recordedPcmData.reset()
            }
            Log.d(TAG, "Recording stopped: $capturedChunksCount streaming chunk(s) already delivered; no in-flight speech.")
            return FloatArray(0)
        }

        // 3. Fallback for non-streaming usage (e.g. push-to-talk in MainActivity or very short utterance)
        val pcmBytes: ByteArray
        synchronized(recordedPcmData) {
            pcmBytes = recordedPcmData.toByteArray()
            recordedPcmData.reset()
        }

        val numSamples = pcmBytes.size / 2
        val floatSamples = FloatArray(numSamples)
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until numSamples) {
            floatSamples[i] = shortBuffer.get(i) / 32768.0f
        }

        Log.d(TAG, "Captured ${floatSamples.size} audio samples (${floatSamples.size / sampleRate.toFloat()}s)")
        return floatSamples
    }

    fun isCurrentlyRecording(): Boolean = isRecording.get()
}
