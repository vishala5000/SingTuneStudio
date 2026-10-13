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

    fun generateProceduralBeat(durationSec: Float, bpm: Int = 120): ShortArray {
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val beat = ShortArray(totalSamples)
        val samplesPerBeat = (60.0 / bpm * SAMPLE_RATE).toInt()
        
        for (i in 0 until totalSamples) {
            val beatPos = i % samplesPerBeat
            val barPos = i % (samplesPerBeat * 4)
            var sample = 0.0

            // Kick Drum
            if (beatPos < SAMPLE_RATE * 0.1) {
                val t = beatPos.toDouble() / SAMPLE_RATE
                val freq = 150.0 * exp(-t * 30) + 50.0
                sample += sin(2 * PI * freq * t) * exp(-t * 15) * 0.8
            }
            // Snare
            if ((barPos / samplesPerBeat) % 2 == 1 && beatPos < SAMPLE_RATE * 0.1) {
                sample += (Math.random() * 2 - 1) * exp(-beatPos.toDouble() / SAMPLE_RATE * 20) * 0.5
            }
            // Hi-Hat
            if (beatPos % (samplesPerBeat / 2) < SAMPLE_RATE * 0.02) {
                sample += (Math.random() * 2 - 1) * exp(-beatPos.toDouble() / SAMPLE_RATE * 80) * 0.2
            }
            // Bassline
            val bassFreq = 110.0 
            sample += sin(2 * PI * bassFreq * i.toDouble() / SAMPLE_RATE) * 0.3 * (if (beatPos < samplesPerBeat / 2) 1.0 else 0.5)

            // Safe Int coercion before Short conversion
            beat[i] = (sample * 32767 * 0.6).toInt().coerceIn(-32768, 32767).toShort()
        }
        return beat
    }

    fun processVocalAndMix(rawVocal: ShortArray, beat: ShortArray): ShortArray {
        val pitched = pitchShift(rawVocal, 1.02f)
        val effected = applyStudioEffects(pitched)
        val compressed = applyCompression(effected)
        
        val finalSize = maxOf(compressed.size, beat.size)
        val finalMix = ShortArray(finalSize)
        for (i in 0 until finalSize) {
            val vocalSample = if (i < compressed.size) compressed[i].toInt() else 0
            val beatSample = if (i < beat.size) beat[i].toInt() else 0
            
            val mixed = (vocalSample * 0.75 + beatSample * 0.6).toInt()
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
        val delaySamples = (SAMPLE_RATE * 0.3).toInt() 
        val reverbDelay1 = (SAMPLE_RATE * 0.05).toInt() 
        val reverbDelay2 = (SAMPLE_RATE * 0.08).toInt() 
        
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
        val ratio = 3f
        
        for (i in audio.indices) {
            val sample = audio[i].toFloat()
            val absSample = abs(sample)
            val outSample = if (absSample > threshold) {
                threshold + (
