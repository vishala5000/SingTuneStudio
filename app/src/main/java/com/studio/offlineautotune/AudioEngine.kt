package com.studio.offlineautotune

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.*

object AudioEngine {
    const val SAMPLE_RATE = 44100
    const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    fun createAudioRecord(): AudioRecord {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_IN,
            ENCODING,
            bufferSize * 2
        )
    }

    // 1. Detect the dominant pitch (Key) of the vocal to match the music
    private fun detectDominantPitch(audio: ShortArray): Float {
        // Analyze the first 2 seconds (or less if recording is short)
        val analyzeLength = minOf(audio.size, SAMPLE_RATE * 2)
        if (analyzeLength < 1024) return 110.0f // Fallback to A2
        
        val buffer = FloatArray(analyzeLength)
        for (i in 0 until analyzeLength) {
            buffer[i] = audio[i].toFloat() / 32768.0f
        }

        // Simple Autocorrelation to find fundamental frequency
        var bestOffset = -1
        var bestCorrelation = 0.0f
        var rms = 0.0f
        
        for (i in 0 until analyzeLength) {
            rms += buffer[i] * buffer[i]
        }
        rms = sqrt(rms / analyzeLength)
        if (rms < 0.01f) return 110.0f // Too quiet, fallback

        var lastCorrelation = 1.0f
        // Search between 220Hz and 440Hz range (typical vocal fundamental)
        for (offset in 100 until minOf(analyzeLength / 2, 2000)) { 
            var correlation = 0.0f
            for (i in 0 until minOf(analyzeLength - offset, 2000)) {
                correlation += abs(buffer[i] - buffer[i + offset])
            }
            correlation = 1.0f - (correlation / minOf(analyzeLength - offset, 2000))
            
            if (correlation > 0.9f && correlation > lastCorrelation) {
                bestCorrelation = correlation
                bestOffset = offset
            }
            lastCorrelation = correlation
        }

        return if (bestOffset > 0) SAMPLE_RATE.toFloat() / bestOffset else 110.0f
    }

    // 2. Procedural Beat Generator (Now Key-Aware)
    fun generateProceduralBeat(durationSec: Float, vocalAudio: ShortArray, bpm: Int = 120): ShortArray {
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val beat = ShortArray(totalSamples)
        val samplesPerBeat = (60.0 / bpm * SAMPLE_RATE).toInt()
        
        // Detect user's singing key and map to a musical bass note
        val detectedPitch = detectDominantPitch(vocalAudio)
        
        // Snap to nearest musical note (using Double for precise math)
        val bassFreq = when {
            detectedPitch < 120f -> 110.00      // A2
            detectedPitch < 150f -> 130.81      // C3
            detectedPitch < 180f -> 146.83      // D3
            detectedPitch < 210f -> 164.81      // E3
            detectedPitch < 240f -> 196.00      // G3
            else -> 220.00                      // A3
        }

        for (i in 0 until totalSamples) {
            val beatPos = i % samplesPerBeat
            val barPos = i % (samplesPerBeat * 4)
            var sample = 0.0

            // Kick Drum (Every beat)
            if (beatPos < SAMPLE_RATE * 0.1) {
                val t = beatPos.toDouble() / SAMPLE_RATE
                val freq = 150.0 * exp(-t * 30) + 50.0
                sample += sin(2 * PI * freq * t) * exp(-t * 15) * 0.8
            }
            // Snare (Beats 2 and 4)
            if ((barPos / samplesPerBeat) % 2 == 1 && beatPos < SAMPLE_RATE * 0.1) {
                sample += (Math.random() * 2 - 1) * exp(-beatPos.toDouble() / SAMPLE_RATE * 20) * 0.5
            }
            // Hi-Hat (Every 8th note)
            if (beatPos % (samplesPerBeat / 2) < SAMPLE_RATE * 0.02) {
                sample += (Math.random() * 2 - 1) * exp(-beatPos.toDouble() / SAMPLE_RATE * 80) * 0.2
            }
            // Bassline (Dynamically matched to user's vocal key!)
            sample += sin(2 * PI * bassFreq * i.toDouble() / SAMPLE_RATE) * 0.35 * (if (beatPos < samplesPerBeat / 2) 1.0 else 0.6)

            beat[i] = (sample * 32767 * 0.6).toInt().coerceIn(-32768, 32767).toShort()
        }
        return beat
    }

    // 3. Studio DSP Pipeline
    fun processVocalAndMix(rawVocal: ShortArray, beat: ShortArray): ShortArray {
        val pitched = pitchShift(rawVocal, 1.02f) // Subtle brightening/tuning
        val effected = applyStudioEffects(pitched) // Reverb & Echo
        val compressed = applyCompression(effected) // Dynamic Compression
        
        val finalSize = maxOf(compressed.size, beat.size)
        val finalMix = ShortArray(finalSize)
        for (i in 0 until finalSize) {
            val vocalSample = if (i < compressed.size) compressed[i].toInt() else 0
            val beatSample = if (i < beat.size) beat[i].toInt() else 0
            
            // Mix: Vocal slightly louder than beat for clarity
            val mixed = (vocalSample * 0.8 + beatSample * 0.5).toInt()
            finalMix[i] = mixed.coerceIn(-32768, 32767).toShort()
        }
        return finalMix
    }

    private fun pitchShift(audio: ShortArray, factor: Float): ShortArray {
        val out = ShortArray(audio.size)
        for (i in out.indices) {
            val srcIndex = i * factor
            val indexLow = srcIndex.toInt()
            val indexHigh = (indexLow + 1).coerceAtMost(audio.size - 1)
            val frac = srcIndex - indexLow
            if (indexLow >= 0 && indexLow < audio.size) {
                val sample = audio[indexLow].toInt() * (1 - frac) + audio[indexHigh].toInt() * frac
                out[i] = sample.toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return out
    }

    private fun applyStudioEffects(audio: ShortArray): ShortArray {
        val out = FloatArray(audio.size)
        val delaySamples = (SAMPLE_RATE * 0.3).toInt() // 300ms Echo
        val reverbDelay1 = (SAMPLE_RATE * 0.05).toInt() // 50ms Reverb
        val reverbDelay2 = (SAMPLE_RATE * 0.08).toInt() // 80ms Reverb
        
        for (i in audio.indices) {
            var sample = audio[i].toFloat()
            if (i >= delaySamples) sample += out[i - delaySamples] * 0.25f
            if (i >= reverbDelay1) sample += out[i - reverbDelay1] * 0.35f
            if (i >= reverbDelay2) sample += out[i - reverbDelay2] * 0.20f
            out[i] = sample
        }
        
        val finalOut = ShortArray(audio.size)
        for (i in out.indices) {
            finalOut[i] = out[i].toInt().coerceIn(-32768, 32767).toShort()
        }
        return finalOut
    }

    private fun applyCompression(audio: ShortArray): ShortArray {
        val out = ShortArray(audio.size)
        val threshold = 15000f
        val ratio = 3.5f
        
        for (i in audio.indices) {
            val sample = audio[i].toFloat()
            val absSample = abs(sample)
            val outSample = if (absSample > threshold) {
                threshold + (absSample - threshold) / ratio
            } else {
                absSample
            }
            val finalSample = if (sample < 0) -outSample else outSample
            out[i] = finalSample.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
