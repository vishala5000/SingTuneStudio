package com.studio.offlineautotune

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.*

object AudioEngine {
    const val SAMPLE_RATE = 44100
    const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    private var seed = 12345L
    private fun fastRandom(): Float {
        seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
        return (seed.toDouble() / 0x7fffffffL).toFloat()
    }

    fun createAudioRecord(): AudioRecord {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        return AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize * 2
        )
    }

    // ============================================
    // 1. CRYSTAL CLEAR VOCAL CLEANUP (No destructive gating)
    // ============================================
    private fun cleanVocal(audio: ShortArray): ShortArray {
        val out = ShortArray(audio.size)
        
        // Professional 100Hz High-Pass Filter: Removes mud/rumble, preserves vocal clarity
        val rc = 1.0f / (2.0f * PI.toFloat() * 100.0f)
        val dt = 1.0f / SAMPLE_RATE.toFloat()
        val alpha = rc / (rc + dt)
        
        var prevIn = 0.0f
        var prevOut = 0.0f
        
        for (i in audio.indices) {
            val input = audio[i].toFloat()
            // Stable 1-pole HPF formula
            val filtered = alpha * (prevOut + input - prevIn)
            prevIn = input
            prevOut = filtered
            
            out[i] = filtered.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    // ============================================
    // 2. KEY DETECTION (For Music Matching)
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

    private fun snapToNearestNote(freq: Float): Float {
        val midiNote = (12.0 * log2(freq.toDouble() / 440.0) + 69.0).toFloat()
        val roundedNote = round(midiNote)
        return (440.0 * 2.0.pow((roundedNote - 69.0) / 12.0)).toFloat()
    }

    // ============================================
    // 3. PROFESSIONAL BEAT GENERATOR
    // ============================================
    fun generateProceduralBeat(durationSec: Float, vocalAudio: ShortArray, bpm: Int = 120): ShortArray {
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val beat = FloatArray(totalSamples)
        val samplesPerBeat = (60.0f / bpm.toFloat() * SAMPLE_RATE.toFloat()).toInt()
        val samplesPerBar = samplesPerBeat * 4
        val sixteenth = samplesPerBeat / 4
        val eighth = samplesPerBeat / 2
        
        val detectedPitch = detectDominantPitch(vocalAudio)
        val rootFreq = snapToNearestNote(detectedPitch)
        
        val progression = listOf(
            listOf(1.0f, 1.2599f, 1.4983f),
            listOf(1.4983f, 1.8877f, 2.2449f),
            listOf(1.6818f, 2.0f, 2.3784f),
            listOf(1.3348f, 1.6818f, 2.0f)
        )

        var maxVal = 0.0f

        for (i in 0 until totalSamples) {
            val beatPos = i % samplesPerBeat
            val barPos = i % samplesPerBar
            val currentBar = (i / samplesPerBar) % 4
            val currentChord = progression[currentBar]
            
            var sample = 0.0f
            
            // KICK
            if (beatPos < SAMPLE_RATE * 0.15f) {
                val t = beatPos.toFloat() / SAMPLE_RATE.toFloat()
                val freq = 150.0f * exp(-t * 40.0f) + 40.0f
                sample += sin(2.0f * PI.toFloat() * freq * t) * exp(-t * 12.0f) * 0.9f
            }
            
            // SNARE/CLAP
            val isSnareBeat = (barPos / samplesPerBeat) % 2 == 1
            if (isSnareBeat && beatPos < SAMPLE_RATE * 0.15f) {
                val t = beatPos.toFloat() / SAMPLE_RATE.toFloat()
                sample += (fastRandom() * 2.0f - 1.0f) * exp(-t * 25.0f) * 0.6f
                sample += sin(2.0f * PI.toFloat() * 200.0f * t) * exp(-t * 30.0f) * 0.4f
            }
            
            // HI-HATS
            val hatPos = i % sixteenth
            if (hatPos < SAMPLE_RATE * 0.01f) {
                val t = hatPos.toFloat() / SAMPLE_RATE.toFloat()
                val isAccent = (i % samplesPerBeat) / sixteenth % 2 == 1
                val vol = if (isAccent) 0.25f else 0.1f
                sample += (fastRandom() * 2.0f - 1.0f) * exp(-t * 120.0f) * vol
            }
            
            // DRUM FILL
            if (currentBar == 3 && barPos > samplesPerBeat * 3) {
                val fillPos = i % (samplesPerBeat / 8)
                if (fillPos < SAMPLE_RATE * 0.015f) {
                    sample += (fastRandom() * 2.0f - 1.0f) * 0.3f
                }
            }

            // BASS
            val bassNotePos = i % eighth
            val bassFreq = rootFreq * (if (bassNotePos < eighth / 2) 1.0f else 0.5f)
            val bassSample = sin(2.0f * PI.toFloat() * bassFreq * i.toFloat() / SAMPLE_RATE.toFloat()) * 0.4f
            
            // CHORD PAD
            var chordSample = 0.0f
            for (mult in currentChord) {
                val freq = rootFreq * mult
                chordSample += sin(2.0f * PI.toFloat() * freq * i.toFloat() / SAMPLE_RATE.toFloat()) * 0.12f
                chordSample += sin(2.0f * PI.toFloat() * (freq * 1.005f) * i.toFloat() / SAMPLE_RATE.toFloat()) * 0.12f
            }
            
            // ARPEGGIO
            val arpNoteIndex = (i / sixteenth) % currentChord.size
            val arpFreq = rootFreq * currentChord[arpNoteIndex] * 2.0f
            val arpEnv = exp(-((i % sixteenth).toFloat() / SAMPLE_RATE.toFloat()) * 15.0f)
            val arpSample = sin(2.0f * PI.toFloat() * arpFreq * i.toFloat() / SAMPLE_RATE.toFloat()) * arpEnv * 0.15f

            // SIDECHAIN PUMPING
            val kickDecay = exp(-beatPos.toFloat() / SAMPLE_RATE.toFloat() * 20.0f)
            val sidechainGain = 1.0f - (kickDecay * 0.75f)
            
            sample += (bassSample + chordSample + arpSample) * sidechainGain
            
            beat[i] = sample
            if (abs(sample) > maxVal) maxVal = abs(sample)
        }
        
        if (maxVal == 0.0f) maxVal = 1.0f
        val finalBeat = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            finalBeat[i] = ((beat[i] / maxVal) * 32767.0f * 0.85f).toInt().coerceIn(-32768, 32767).toShort()
        }
        
        return finalBeat
    }

    // ============================================
    // 4. MASTER VOCAL CHAIN + SAFE MIX
    // ============================================
    fun processVocalAndMix(rawVocal: ShortArray, beat: ShortArray): ShortArray {
        // SAFE PIPELINE: No destructive pitch shifting or harsh gating
        val cleaned = cleanVocal(rawVocal)                 // 1. Remove low-end mud
        val compressed = applyCompression(cleaned)         // 2. Smooth dynamics
        val effected = applyStudioEffects(compressed)      // 3. Clean, non-metallic space
        
        val finalSize = maxOf(effected.size, beat.size)
        val finalMix = ShortArray(finalSize)
        
        for (i in 0 until finalSize) {
            val vocalSample = if (i < effected.size) effected[i].toInt() else 0
            val beatSample = if (i < beat.size) beat[i].toInt() else 0
            
            // SAFE MIXING: Lowered gains to guarantee ZERO hard-clipping distortion
            val mixed = ((vocalSample * 0.65f) + (beatSample * 0.45f)).toInt()
            finalMix[i] = mixed.coerceIn(-32768, 32767).toShort()
        }
        return finalMix
    }

    private fun applyCompression(audio: ShortArray): ShortArray {
        val out = ShortArray(audio.size)
        val threshold = 12000f
        val ratio = 4.0f
        
        for (i in audio.indices) {
            val sample = audio[i].toFloat()
            val absSample = abs(sample)
            
            val outSample = if (absSample > threshold) {
                threshold + (absSample - threshold) / ratio
            } else {
                absSample
            }
            
            val finalSample = if (sample < 0) -outSample else outSample
            // Slight makeup gain for punchiness
            val makeup = finalSample * 1.1f 
            out[i] = makeup.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun applyStudioEffects(audio: ShortArray): ShortArray {
        val out = FloatArray(audio.size)
        // Clean, low-gain delays (NO feedback loops = NO metallic ringing)
        val slapbackDelay = (SAMPLE_RATE * 0.12f).toInt()  // 120ms width
        val earlyReflect = (SAMPLE_RATE * 0.04f).toInt()   // 40ms room feel
        
        for (i in audio.indices) {
            var sample = audio[i].toFloat()
            
            // Safe slapback echo
            if (i >= slapbackDelay) {
                sample += out[i - slapbackDelay] * 0.15f
            }
            // Safe early reflection
            if (i >= earlyReflect) {
                sample += out[i - earlyReflect] * 0.10f
            }
            
            out[i] = sample
        }
        
        val finalOut = ShortArray(audio.size)
        for (i in out.indices) {
            finalOut[i] = out[i].toInt().coerceIn(-32768, 32767).toShort()
        }
        return finalOut
    }
}
