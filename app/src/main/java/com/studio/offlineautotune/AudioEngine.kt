package com.studio.offlineautotune

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.*

object AudioEngine {
    const val SAMPLE_RATE = 44100
    const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    // Fast pseudo-random generator (prevents CPU lag)
    private var seed = 12345L
    private fun fastRandom(): Double {
        seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
        return seed.toDouble() / 0x7fffffffL
    }

    fun createAudioRecord(): AudioRecord {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        return AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize * 2
        )
    }

    // ============================================
    // 1. ADVANCED NOISE REMOVAL PIPELINE
    // ============================================
    private fun removeNoise(audio: ShortArray): ShortArray {
        if (audio.isEmpty()) return audio
        
        val out = FloatArray(audio.size)
        
        // A. Adaptive Noise Floor Detection (first 500ms)
        val noiseProfileSamples = minOf(SAMPLE_RATE / 2, audio.size)
        var noiseRMS = 0.0
        for (i in 0 until noiseProfileSamples) {
            val s = audio[i].toFloat()
            noiseRMS += s * s
        }
        noiseRMS = sqrt(noiseRMS / noiseProfileSamples)
        val gateThreshold = (noiseRMS * 3.0).coerceAtLeast(400.0)
        
        // B. High-pass filter coefficients (80Hz cutoff - removes rumble)
        val rc = 1.0 / (2 * PI * 80)
        val dt = 1.0 / SAMPLE_RATE
        val alpha = rc / (rc + dt)
        
        var prevIn = 0.0
        var prevOut = 0.0
        
        // C. Apply smooth noise gate + high-pass filter
        for (i in audio.indices) {
            val input = audio[i].toFloat()
            val absInput = abs(input)
            
            // Smooth noise gate (quadratic expander - no clicks)
            val gateGain = if (absInput > gateThreshold) {
                1.0
            } else {
                val ratio = absInput / gateThreshold
                ratio * ratio // Quadratic curve for smooth reduction
            }
            val gated = input * gateGain
            
            // High-pass filter (removes low-frequency rumble)
            val filtered = alpha * (prevOut + gated - prevIn)
            prevIn = gated
            prevOut = filtered
            
            out[i] = filtered
        }
        
        // Convert back to ShortArray
        val finalOut = ShortArray(audio.size)
        for (i in out.indices) {
            finalOut[i] = out[i].toInt().coerceIn(-32768, 32767).toShort()
        }
        return finalOut
    }

    // ============================================
    // 2. KEY DETECTION
    // ============================================
    private fun detectDominantPitch(audio: ShortArray): Float {
        val analyzeLength = minOf(audio.size, SAMPLE_RATE * 2)
        if (analyzeLength < 1024) return 110.0f
        
        val buffer = FloatArray(analyzeLength)
        for (i in 0 until analyzeLength) buffer[i] = audio[i].toFloat() / 32768.0f

        var bestOffset = -1
        var bestCorrelation = 0.0f
        var rms = 0.0f
        
        for (i in 0 until analyzeLength) rms += buffer[i] * buffer[i]
        rms = sqrt(rms / analyzeLength)
        if (rms < 0.01f) return 110.0f 

        var lastCorrelation = 1.0f
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

    private fun snapToNearestNote(freq: Float): Float {
        val midiNote = 12 * log2(freq / 440.0) + 69
        val roundedNote = round(midiNote)
        return 440.0 * 2.0.pow((roundedNote - 69) / 12.0).toFloat()
    }

    // ============================================
    // 3. PROFESSIONAL BEAT GENERATOR
    // ============================================
    fun generateProceduralBeat(durationSec: Float, vocalAudio: ShortArray, bpm: Int = 120): ShortArray {
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val beat = FloatArray(totalSamples)
        val samplesPerBeat = (60.0 / bpm * SAMPLE_RATE).toInt()
        val samplesPerBar = samplesPerBeat * 4
        val sixteenth = samplesPerBeat / 4
        val eighth = samplesPerBeat / 2
        
        val detectedPitch = detectDominantPitch(vocalAudio)
        val rootFreq = snapToNearestNote(detectedPitch)
        
        // I - V - vi - IV Pop Progression
        val progression = listOf(
            listOf(1.0, 1.2599, 1.4983),    // I
            listOf(1.4983, 1.8877, 2.2449), // V
            listOf(1.6818, 2.0, 2.3784),    // vi
            listOf(1.3348, 1.6818, 2.0)     // IV
        )

        var maxVal = 0.0

        for (i in 0 until totalSamples) {
            val beatPos = i % samplesPerBeat
            val barPos = i % samplesPerBar
            val currentBar = (i / samplesPerBar) % 4
            val currentChord = progression[currentBar]
            
            var sample = 0.0
            
            // KICK
            if (beatPos < SAMPLE_RATE * 0.15) {
                val t = beatPos.toDouble() / SAMPLE_RATE
                val freq = 150.0 * exp(-t * 40) + 40.0
                sample += sin(2 * PI * freq * t) * exp(-t * 12) * 0.9
            }
            
            // SNARE/CLAP
            val isSnareBeat = (barPos / samplesPerBeat) % 2 == 1
            if (isSnareBeat && beatPos < SAMPLE_RATE * 0.15) {
                val t = beatPos.toDouble() / SAMPLE_RATE
                sample += (fastRandom() * 2 - 1) * exp(-t * 25) * 0.6
                sample += sin(2 * PI * 200 * t) * exp(-t * 30) * 0.4
            }
            
            // HI-HATS (16th notes with accents)
            val hatPos = i % sixteenth
            if (hatPos < SAMPLE_RATE * 0.01) {
                val t = hatPos.toDouble() / SAMPLE_RATE
                val isAccent = (i % samplesPerBeat) / sixteenth % 2 == 1
                val vol = if (isAccent) 0.25 else 0.1
                sample += (fastRandom() * 2 - 1) * exp(-t * 120) * vol
            }
            
            // DRUM FILL (End of 4th bar)
            if (currentBar == 3 && barPos > samplesPerBeat * 3) {
                val fillPos = i % (samplesPerBeat / 8)
                if (fillPos < SAMPLE_RATE * 0.015) {
                    sample += (fastRandom() * 2 - 1) * 0.3
                }
            }

            // BASS (Rhythmic 8th notes)
            val bassNotePos = i % eighth
            val bassFreq = rootFreq * (if (bassNotePos < eighth / 2) 1.0 else 0.5)
            val bassSample = sin(2 * PI * bassFreq * i.toDouble() / SAMPLE_RATE) * 0.4
            
            // CHORD PAD (Detuned stack)
            var chordSample = 0.0
            for (mult in currentChord) {
                val freq = rootFreq * mult
                chordSample += sin(2 * PI * freq * i.toDouble() / SAMPLE_RATE) * 0.12
                chordSample += sin(2 * PI * (freq * 1.005) * i.toDouble() / SAMPLE_RATE) * 0.12
            }
            
            // ARPEGGIO
            val arpNoteIndex = (i / sixteenth) % currentChord.size
            val arpFreq = rootFreq * currentChord[arpNoteIndex] * 2.0
            val arpEnv = exp(-((i % sixteenth).toDouble() / SAMPLE_RATE) * 15)
            val arpSample = sin(2 * PI * arpFreq * i.toDouble() / SAMPLE_RATE) * arpEnv * 0.15

            // SIDECHAIN PUMPING
            val kickDecay = exp(-beatPos.toDouble() / SAMPLE_RATE * 20)
            val sidechainGain = 1.0 - (kickDecay * 0.75)
            
            sample += (bassSample + chordSample + arpSample) * sidechainGain
            
            beat[i] = sample
            if (abs(sample) > maxVal) maxVal = abs(sample)
        }
        
        if (maxVal == 0.0) maxVal = 1.0
        val finalBeat = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            finalBeat[i] = ((beat[i] / maxVal) * 32767 * 0.85).toInt().coerceIn(-32768, 32767).toShort()
        }
        
        return finalBeat
    }

    // ============================================
    // 4. MASTER VOCAL CHAIN + MIX
    // ============================================
    fun processVocalAndMix(rawVocal: ShortArray, beat: ShortArray): ShortArray {
        // PIPELINE ORDER (Critical for professional sound):
        val cleaned = removeNoise(rawVocal)           // 1. Remove background noise
        val pitched = pitchShift(cleaned, 1.02f)      // 2. Subtle auto-tune
        val effected = applyStudioEffects(pitched)    // 3. Reverb + Echo
        val compressed = applyCompression(effected)   // 4. Dynamic compression
        
        val finalSize = maxOf(compressed.size, beat.size)
        val finalMix = ShortArray(finalSize)
        for (i in 0 until finalSize) {
            val vocalSample = if (i < compressed.size) compressed[i].toInt() else 0
            val beatSample = if (i < beat.size) beat[i].toInt() else 0
            
            val mixed = (vocalSample * 0.85 + beatSample * 0.6).toInt()
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
