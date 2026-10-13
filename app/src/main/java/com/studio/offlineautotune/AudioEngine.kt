package com.studio.offlineautotune

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

// Effect Preset Configuration
data class EffectPreset(
    val name: String,
    val reverbDecay: Float,      // 0.0 - 1.0
    val echoDelay: Float,        // seconds
    val echoFeedback: Float,     // 0.0 - 1.0
    val stereoWidth: Float,      // 0.0 - 1.0
    val compressionRatio: Float, // 2.0 - 10.0
    val clarityBoost: Float      // 0.0 - 2.0
)

object AudioEngine {
    const val SAMPLE_RATE = 44100
    const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    // Professional Effect Presets
    val PRESETS = mapOf(
        "Pop" to EffectPreset("Pop", 0.6f, 0.25f, 0.3f, 0.4f, 4.0f, 1.5f),
        "Rock" to EffectPreset("Rock", 0.4f, 0.3f, 0.25f, 0.3f, 6.0f, 1.8f),
        "Ballad" to EffectPreset("Ballad", 0.8f, 0.4f, 0.4f, 0.6f, 3.0f, 1.2f),
        "Ambient" to EffectPreset("Ambient", 0.9f, 0.5f, 0.5f, 0.8f, 2.5f, 1.0f)
    )

    private var currentPreset = PRESETS["Pop"]!!

    fun setPreset(presetName: String) {
        currentPreset = PRESETS[presetName] ?: PRESETS["Pop"]!!
    }

    fun createAudioRecord(): AudioRecord {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        return AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize * 2
        )
    }

    // Robust WAV loader
    private fun loadWavFromRaw(context: Context, resourceId: Int): ShortArray {
        val inputStream = context.resources.openRawResource(resourceId)
        val byteArrayOutputStream = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            byteArrayOutputStream.write(buffer, 0, bytesRead)
        }
        val bytes = byteArrayOutputStream.toByteArray()
        
        var dataOffset = 12
        while (dataOffset < bytes.size - 8) {
            val chunkId = String(bytes, dataOffset, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, dataOffset + 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN).getInt()
            if (chunkId == "data") {
                dataOffset += 8
                break
            }
            dataOffset += 8 + chunkSize
        }
        
        val channels = if (bytes.size > 23) {
            ByteBuffer.wrap(bytes, 22, 2).order(ByteOrder.LITTLE_ENDIAN).getShort().toInt()
        } else 1
        
        val pcmBytes = bytes.copyOfRange(dataOffset, bytes.size)
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val rawSamples = ShortArray(shortBuffer.remaining())
        shortBuffer.get(rawSamples)
        
        return if (channels == 2) {
            val mono = ShortArray(rawSamples.size / 2)
            for (i in mono.indices) {
                mono[i] = ((rawSamples[i * 2].toInt() + rawSamples[i * 2 + 1].toInt()) / 2).toShort()
            }
            mono
        } else {
            rawSamples
        }
    }

    private fun loopAudio(audio: ShortArray, durationSec: Float): ShortArray {
        if (audio.isEmpty()) return ShortArray(0)
        val targetSamples = (SAMPLE_RATE * durationSec).toInt()
        val looped = ShortArray(targetSamples)
        for (i in 0 until targetSamples) {
            looped[i] = audio[i % audio.size]
        }
        return looped
    }

    fun generateProceduralBeat(context: Context, durationSec: Float, vocalAudio: ShortArray): ShortArray {
        val drumLoop = loadWavFromRaw(context, R.raw.drum_loop)
        val bassLoop = loadWavFromRaw(context, R.raw.bass_loop)
        val chordLoop = loadWavFromRaw(context, R.raw.chord_loop)
        val arpLoop = loadWavFromRaw(context, R.raw.arp_loop)
        
        val drums = loopAudio(drumLoop, durationSec)
        val bass = loopAudio(bassLoop, durationSec)
        val chords = loopAudio(chordLoop, durationSec)
        val arp = loopAudio(arpLoop, durationSec)
        
        val totalSamples = drums.size
        val beat = ShortArray(totalSamples)
        
        for (i in 0 until totalSamples) {
            val drumSample = drums[i].toFloat()
            val bassSample = bass[i].toFloat()
            val chordSample = chords[i].toFloat()
            val arpSample = arp[i].toFloat()
            
            val mixed = (drumSample * 0.85f + 
                        bassSample * 0.70f + 
                        chordSample * 0.35f + 
                        arpSample * 0.25f).toInt()
            
            beat[i] = mixed.coerceIn(-32768, 32767).toShort()
        }
        return beat
    }

    // Advanced pitch detection with key display
    fun detectKeyAndBPM(audio: ShortArray): Pair<String, Int> {
        val detectedPitch = detectDominantPitch(audio)
        val key = pitchToNoteName(detectedPitch)
        val bpm = 120 // Fixed BPM since loops are 120 BPM
        return Pair(key, bpm)
    }

    private fun detectDominantPitch(audio: ShortArray): Float {
        val analyzeLength = minOf(audio.size, SAMPLE_RATE * 2)
        if (analyzeLength < 1024) return 110.0f
        
        val buffer = FloatArray(analyzeLength)
        for (i in 0 until analyzeLength) buffer[i] = audio[i].toFloat() / 32768.0f

        var bestOffset = -1
        var bestCorrelation = 0.0f
        var rms = 0.0f
        
        for (i in 0 until analyzeLength) rms += buffer[i] * buffer[i]
        rms = sqrt(rms / analyzeLength.toFloat())
        if (rms < 0.01f) return 110.0f 

        var lastCorrelation = 1.0f
        for (offset in 100 until minOf(analyzeLength / 2, 2000)) { 
            var correlation = 0.0f
            for (i in 0 until minOf(analyzeLength - offset, 2000)) {
                correlation += abs(buffer[i] - buffer[i + offset])
            }
            correlation = 1.0f - (correlation / minOf(analyzeLength - offset, 2000).toFloat())
            
            if (correlation > 0.9f && correlation > lastCorrelation) {
                bestCorrelation = correlation
                bestOffset = offset
            }
            lastCorrelation = correlation
        }
        return if (bestOffset > 0) SAMPLE_RATE.toFloat() / bestOffset.toFloat() else 110.0f
    }

    private fun pitchToNoteName(freq: Float): String {
        val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val midiNote = (12.0 * log2(freq.toDouble() / 440.0) + 69.0).toInt()
        val noteIndex = ((midiNote % 12) + 12) % 12
        return noteNames[noteIndex]
    }

    // Advanced vocal processing with preset-based effects
    private fun autoTuneToAmScale(audio: ShortArray): ShortArray {
        val out = ShortArray(audio.size)
        val windowSize = 2048
        
        val amScale = listOf(
            220.0f, 246.94f, 261.63f, 293.66f, 329.63f, 349.23f, 392.0f, 440.0f,
            493.88f, 523.25f, 587.33f, 659.25f, 698.46f, 783.99f, 880.0f
        )
        
        var i = 0
        while (i < audio.size) {
            val end = minOf(i + windowSize, audio.size)
            val window = audio.copyOfRange(i, end)
            
            val detectedPitch = detectPitchInWindow(window)
            
            if (detectedPitch > 0) {
                val nearestNote = amScale.minByOrNull { abs(it - detectedPitch) } ?: detectedPitch
                val shiftFactor = nearestNote / detectedPitch
                
                if (abs(shiftFactor - 1.0f) < 0.05f) {
                    for (j in 0 until window.size) {
                        val srcIndex = j * shiftFactor
                        val indexLow = srcIndex.toInt()
                        val indexHigh = (indexLow + 1).coerceAtMost(window.size - 1)
                        val frac = srcIndex - indexLow
                        if (indexLow >= 0 && indexLow < window.size) {
                            val sample = window[indexLow].toFloat() * (1.0f - frac) + 
                                        window[indexHigh].toFloat() * frac
                            val globalIndex = i + j
                            if (globalIndex < out.size) {
                                out[globalIndex] = sample.toInt().coerceIn(-32768, 32767).toShort()
                            }
                        }
                    }
                } else {
                    for (j in 0 until window.size) {
                        val globalIndex = i + j
                        if (globalIndex < out.size) {
                            out[globalIndex] = window[j]
                        }
                    }
                }
            } else {
                for (j in 0 until window.size) {
                    val globalIndex = i + j
                    if (globalIndex < out.size) {
                        out[globalIndex] = window[j]
                    }
                }
            }
            
            i += windowSize
        }
        return out
    }

    private fun detectPitchInWindow(audio: ShortArray): Float {
        if (audio.size < 512) return 0f
        
        val buffer = FloatArray(audio.size)
        for (i in audio.indices) buffer[i] = audio[i].toFloat() / 32768.0f

        var bestOffset = -1
        var bestCorrelation = 0.0f
        var rms = 0.0f
        
        for (i in buffer.indices) rms += buffer[i] * buffer[i]
        rms = sqrt(rms / buffer.size)
        if (rms < 0.02f) return 0f

        var lastCorrelation = 1.0f
        for (offset in 50 until minOf(buffer.size / 2, 1000)) {
            var correlation = 0.0f
            for (i in 0 until minOf(buffer.size - offset, 1000)) {
                correlation += abs(buffer[i] - buffer[i + offset])
            }
            correlation = 1.0f - (correlation / minOf(buffer.size - offset, 1000))
            
            if (correlation > 0.85f && correlation > lastCorrelation) {
                bestCorrelation = correlation
                bestOffset = offset
            }
            lastCorrelation = correlation
        }
        
        return if (bestOffset > 0) SAMPLE_RATE.toFloat() / bestOffset.toFloat() else 0f
    }

    // De-esser: Reduces harsh "S" sounds (5-9 kHz range)
    private fun deEss(audio: ShortArray): ShortArray {
        val out = ShortArray(audio.size)
        val threshold = 8000f
        val reduction = 0.5f
        
        // Simple high-pass to detect sibilance
        var prev = 0.0f
        for (i in audio.indices) {
            val current = audio[i].toFloat()
            val highFreq = current - prev
            prev = current
            
            val sibilance = abs(highFreq)
            val gain = if (sibilance > threshold) reduction else 1.0f
            
            out[i] = (current * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun removeMud(audio: ShortArray): ShortArray {
        val out = ShortArray(audio.size)
        val rc = 1.0f / (2.0f * PI.toFloat() * 80.0f)
        val dt = 1.0f / SAMPLE_RATE.toFloat()
        val alpha = rc / (rc + dt)
        var prevIn = 0.0f
        var prevOut = 0.0f
        for (i in audio.indices) {
            val input = audio[i].toFloat()
            val filtered = alpha * (prevOut + input - prevIn)
            prevIn = input
            prevOut = filtered
            out[i] = filtered.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun addClarity(audio: ShortArray, boost: Float): ShortArray {
        val out = ShortArray(audio.size)
        var prev = 0.0f
        for (i in audio.indices) {
            val current = audio[i].toFloat()
            val diff = current - prev
            prev = current
            val boosted = current + (diff * boost)
            out[i] = boosted.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun applyCompression(audio: ShortArray, ratio: Float): ShortArray {
        val out = ShortArray(audio.size)
        val threshold = 12000f
        for (i in audio.indices) {
            val sample = audio[i].toFloat()
            val absSample = abs(sample)
            val outSample = if (absSample > threshold) threshold + (absSample - threshold) / ratio else absSample
            val finalSample = if (sample < 0) -outSample else outSample
            out[i] = (finalSample * 1.15f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    // Advanced stereo reverb with preset-based decay
    private fun applyAdvancedReverb(audio: ShortArray, preset: EffectPreset): ShortArray {
        val out = FloatArray(audio.size)
        val echoDelay = (SAMPLE_RATE * preset.echoDelay).toInt()
        val reverbDecay = preset.reverbDecay
        
        // Multiple reverb taps for lush, professional sound
        val taps = listOf(
            Triple((SAMPLE_RATE * 0.023f).toInt(), 0.4f * reverbDecay, 0.7f),
            Triple((SAMPLE_RATE * 0.037f).toInt(), 0.3f * reverbDecay, 0.6f),
            Triple((SAMPLE_RATE * 0.059f).toInt(), 0.25f * reverbDecay, 0.5f),
            Triple((SAMPLE_RATE * 0.089f).toInt(), 0.2f * reverbDecay, 0.4f),
            Triple((SAMPLE_RATE * 0.131f).toInt(), 0.15f * reverbDecay, 0.3f)
        )
        
        for (i in audio.indices) {
            val dry = audio[i].toFloat()
            var reverbWet = 0.0f
            var echoWet = 0.0f
            
            // Echo
            if (i >= echoDelay) {
                echoWet = out[i - echoDelay] * preset.echoFeedback
            }
            
            // Multi-tap reverb
            for ((delay, gain, _) in taps) {
                if (i >= delay) {
                    reverbWet += audio[i - delay].toFloat() * gain
                }
            }
            
            out[i] = (dry * 0.6f) + echoWet + reverbWet
        }
        
        val finalOut = ShortArray(audio.size)
        for (i in out.indices) finalOut[i] = out[i].toInt().coerceIn(-32768, 32767).toShort()
        return finalOut
    }

    // Stereo widening using Haas effect
    private fun widenStereo(audio: ShortArray, width: Float): ShortArray {
        val out = ShortArray(audio.size)
        val delaySamples = (SAMPLE_RATE * 0.0003f * width).toInt() // 0.3ms max delay
        
        for (i in audio.indices) {
            val current = audio[i].toFloat()
            val delayed = if (i >= delaySamples) audio[i - delaySamples].toFloat() else current
            
            // Create stereo width by delaying one channel slightly
            val widened = (current * 0.7f + delayed * 0.3f)
            out[i] = widened.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    fun processVocalAndMix(rawVocal: ShortArray, beat: ShortArray): ShortArray {
        // Advanced vocal chain with preset-based processing
        val tuned = autoTuneToAmScale(rawVocal)
        val deessed = deEss(tuned)
        val clean = removeMud(deessed)
        val clear = addClarity(clean, currentPreset.clarityBoost)
        val compressed = applyCompression(clear, currentPreset.compressionRatio)
        val reverbed = applyAdvancedReverb(compressed, currentPreset)
        val widened = widenStereo(reverbed, currentPreset.stereoWidth)
        
        val finalSize = maxOf(widened.size, beat.size)
        val finalMix = ShortArray(finalSize)
        for (i in 0 until finalSize) {
            val vocalSample = if (i < widened.size) widened[i].toInt() else 0
            val beatSample = if (i < beat.size) beat[i].toInt() else 0
            val mixed = ((vocalSample * 0.85f) + (beatSample * 0.45f)).toInt()
            finalMix[i] = mixed.coerceIn(-32768, 32767).toShort()
        }
        return finalMix
    }

    // Real-time waveform data for visualization
    fun getWaveformData(audio: ShortArray, numPoints: Int): List<Float> {
        if (audio.isEmpty()) return List(numPoints) { 0f }
        
        val points = mutableListOf<Float>()
        val samplesPerPoint = audio.size / numPoints
        
        for (i in 0 until numPoints) {
            val start = i * samplesPerPoint
            val end = minOf(start + samplesPerPoint, audio.size)
            var max = 0f
            
            for (j in start until end) {
                val abs = abs(audio[j].toFloat())
                if (abs > max) max = abs
            }
            
            points.add(max / 32768f)
        }
        
        return points
    }
}
