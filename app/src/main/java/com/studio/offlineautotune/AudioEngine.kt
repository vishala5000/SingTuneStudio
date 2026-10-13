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

    // 1. ADVANCED NOISE REMOVAL PIPELINE (Type-Safe)
    private fun removeNoise(audio: ShortArray): ShortArray {
        if (audio.isEmpty()) return audio
        
        val out = FloatArray(audio.size)
        
        val noiseProfileSamples = minOf(SAMPLE_RATE / 2, audio.size)
        var noiseRms = 0.0f
        for (i in 0 until noiseProfileSamples) {
            val s = audio[i].toFloat()
            noiseRms += s * s
        }
        noiseRms = sqrt(noiseRms / noiseProfileSamples.toFloat())
        val gateThreshold = (noiseRms * 3.0f).coerceAtLeast(400.0f)
        
        val rc = 1.0f / (2.0f * PI.toFloat() * 80.0f)
        val dt = 1.0f / SAMPLE_RATE.toFloat()
        val alpha = rc / (rc + dt)
        
        var prevIn = 0.0f
        var prevOut = 0.0f
        
        for (i in audio.indices) {
            val input = audio[i].toFloat()
            val absInput = abs(input)
            
            val gateGain = if (absInput > gateThreshold) {
                1.0f
            } else {
                val ratio = absInput / gateThreshold
                ratio * ratio
            }
            val gated = input * gateGain
            
            val filtered = alpha * (prevOut + gated - prevIn)
            prevIn = gated
            prevOut = filtered
            
            out[i] = filtered
        }
        
        val finalOut = ShortArray(audio.size)
        for (i in out.indices) {
            finalOut[i] = out[i].toInt().coerceIn(-32768, 32767).toShort()
        }
        return finalOut
    }

    // 2. KEY DETECTION (Type-Safe)
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

    // 3. PROFESSIONAL BEAT GENERATOR (Type-Safe)
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

    // 4. MASTER VOCAL CHAIN + MIX (Type-Safe)
    fun processVocalAndMix(rawVocal: ShortArray, beat: ShortArray): ShortArray {
        val cleaned = removeNoise(rawVocal)
        val pitched = pitchShift(cleaned, 1.02f)
        val effected = applyStudioEffects(pitched)
        val compressed = applyCompression(effected)
        
        val finalSize = maxOf(compressed.size, beat.size)
        val finalMix = ShortArray(finalSize)
        for (i in 0 until finalSize) {
            val vocalSample = if (i < compressed.size) compressed[i].toInt() else 0
            val beatSample = if (i < beat.size) beat[i].toInt() else 0
            
            val mixed = (vocalSample * 0.85f + beatSample * 0.6f).toInt()
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
                val sample = audio[indexLow].toInt() * (1.0f - frac) + audio[indexHigh].toInt() * frac
                out[i] = sample.toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return out
    }

    private fun applyStudioEffects(audio: ShortArray): ShortArray {
        val out = FloatArray(audio.size)
        val delaySamples = (SAMPLE_RATE * 0.3f).toInt()
        val reverbDelay1 = (SAMPLE_RATE * 0.05f).toInt()
        val reverbDelay2 = (SAMPLE_RATE * 0.08f).toInt()
        
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
