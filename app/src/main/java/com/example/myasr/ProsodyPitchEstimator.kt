package com.example.myasr

/**
 * Lightweight, zero-dependency time-domain fundamental frequency (F0) pitch contour estimator.
 * Analyzes the terminal ~200ms of PCM speech audio to detect rising pitch contours (question intonation).
 *
 * Latency budget: < 0.2ms on modern mobile CPU.
 */
object ProsodyPitchEstimator {

    /**
     * Estimates pitch contour slope in semitones in the terminal ~200ms of audio.
     * Positive values indicate rising pitch (e.g. +3.5 semitones for question intonation).
     * Returns 0f if audio is unvoiced, too short, or pitch contour is flat.
     *
     * @param samples Normalized 16kHz mono PCM float audio [-1.0, 1.0].
     * @param sampleRate Sampling rate (default 16000 Hz).
     * @return Pitch change in semitones between penultimate and ultimate analysis frames.
     */
    fun estimateTerminalPitchSlope(samples: FloatArray, sampleRate: Int = 16000): Float {
        // Require at least 200ms of audio (3200 samples at 16kHz)
        val minSamples = (sampleRate * 0.20).toInt()
        if (samples.size < minSamples) return 0f

        val frameLen = (sampleRate * 0.05).toInt() // 50ms = 800 samples
        val minLag = (sampleRate / 420.0).toInt()  // 420 Hz = ~38 samples
        val maxLag = (sampleRate / 75.0).toInt()   // 75 Hz = ~213 samples

        // Frame 1 (early terminal window): -180ms to -130ms
        // Frame 2 (late terminal window):  -70ms to -20ms
        val endIdx = samples.size
        val f1Start = endIdx - (sampleRate * 0.18).toInt()
        val f2Start = endIdx - (sampleRate * 0.07).toInt()

        if (f1Start < 0 || f2Start + frameLen > endIdx) return 0f

        val (f1Pitch, f1Voiced) = estimateFramePitch(samples, f1Start, frameLen, minLag, maxLag, sampleRate)
        val (f2Pitch, f2Voiced) = estimateFramePitch(samples, f2Start, frameLen, minLag, maxLag, sampleRate)

        if (!f1Voiced || !f2Voiced || f1Pitch <= 0f || f2Pitch <= 0f) return 0f

        val ratio = f2Pitch / f1Pitch
        if (ratio <= 0.0) return 0f

        // Convert frequency ratio to semitones: 12 * log2(f2 / f1)
        val semitones = (12.0 * (Math.log(ratio.toDouble()) / Math.log(2.0))).toFloat()
        return semitones
    }

    private fun estimateFramePitch(
        samples: FloatArray,
        start: Int,
        len: Int,
        minLag: Int,
        maxLag: Int,
        sampleRate: Int
    ): Pair<Float, Boolean> {
        var e0 = 0.0
        for (i in 0 until len) {
            val v = samples[start + i].toDouble()
            e0 += v * v
        }
        // If energy is below silence noise floor, treat as unvoiced
        if (e0 < 1e-4) return Pair(0f, false)

        var bestLag = 0
        var bestNormCorr = 0.0

        for (lag in minLag..maxLag) {
            if (start + len + lag > samples.size) break
            var corr = 0.0
            var eLag = 0.0
            for (i in 0 until len) {
                val s0 = samples[start + i].toDouble()
                val sLag = samples[start + i + lag].toDouble()
                corr += s0 * sLag
                eLag += sLag * sLag
            }
            if (eLag > 1e-6) {
                val normCorr = corr / Math.sqrt(e0 * eLag)
                if (normCorr > bestNormCorr) {
                    bestNormCorr = normCorr
                    bestLag = lag
                }
            }
        }

        // Voiced human speech typically exhibits autocorrelation peak > 0.45
        val isVoiced = bestNormCorr >= 0.45 && bestLag > 0
        val pitch = if (isVoiced) (sampleRate.toFloat() / bestLag) else 0f
        return Pair(pitch, isVoiced)
    }
}
