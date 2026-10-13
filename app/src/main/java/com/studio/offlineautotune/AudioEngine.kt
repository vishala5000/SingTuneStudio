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
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize * 2
        )
    }

    // ============================================
    // 1. DETECT VOCAL KEY (Autocorrelation)
    // ============================================
    fun detectRootKey(audio: ShortArray): Float {
        val analyzeLength = minOf(audio.size, SAMPLE_RATE * 3)
        if (analyzeLength < 2048) return 261.63f

        val buffer = FloatArray(analyzeLength)
        for (i in 0 until analyzeLength) buffer[i] = audio[i].toFloat() / 32768.0f

        var bestOffset = -1
        var bestCorrelation = -1.0f
        var rms = 0.0f
        for (i in 0 until analyzeLength) rms += buffer[i] * buffer[i]
        rms = sqrt(rms / analyzeLength)
        if (rms < 0.01f) return 261.63f

        // Search typical male/female vocal range (80Hz to 500Hz)
        val minOffset = (SAMPLE_RATE / 500)
        val maxOffset = (SAMPLE_RATE / 80)
        
        var prevCorrelation = 0.0f
        for (offset in minOffset until minOf(maxOffset, analyzeLength / 2)) {
            var correlation = 0.0f
            val count = minOf(analyzeLength - offset, 4000)
            for (i in 0 until count) {
                correlation += buffer[i] * buffer[i + offset]
            }
            correlation /= count
            
            if (correlation > bestCorrelation && correlation > 0.1f) {
                // Check it's a true peak (not just rising)
                if (prevCorrelation > 0 && correlation <= prevCorrelation) {
                    bestCorrelation = correlation
                    bestOffset = offset
                }
            }
            prevCorrelation = correlation
        }
        
        return if (bestOffset > 0) SAMPLE_RATE.toFloat() / bestOffset.toFloat() else 261.63f
    }

    private fun freqToNoteName(freq: Float): String {
        val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val midiNote = (12.0 * log2(freq.toDouble() / 440.0) + 69.0)
        val rounded = round(midiNote).toInt()
        val noteIndex = ((rounded % 12) + 12) % 12
        return noteNames[noteIndex]
    }

    fun getKeyDisplay(audio: ShortArray): String {
        val freq = detectRootKey(audio)
        return freqToNoteName(freq)
    }

    // ============================================
    // 2. GENTLE AUTO-TUNE (Scale Quantization)
    // ============================================
    private fun applyAutoTune(audio: ShortArray, rootFreq: Float): ShortArray {
        val out = ShortArray(audio.size)
        val windowSize = 2048
        val hopSize = 512
        
        // Build Major scale notes across multiple octaves
        val semitones = listOf(0, 2, 4, 5, 7, 9, 11)
        val scaleNotes = mutableListOf<Float>()
        for (octave in -1..2) {
            val octaveMult = 2.0f.pow(octave)
            for (semi in semitones) {
                scaleNotes.add(rootFreq * octaveMult * 2.0f.pow(semi / 12.0f))
            }
        }

        var i = 0
        while (i < audio.size) {
            val end = minOf(i + windowSize, audio.size)
            val window = audio.copyOfRange(i, end)
            
            val detectedPitch = detectPitchInWindow(window)
            
            if (detectedPitch > 80f && detectedPitch < 1000f) {
                // Find nearest scale note
                var closestNote = detectedPitch
                var minDiff = Float.MAX_VALUE
                for (note in scaleNotes) {
                    // Compare in cents (logarithmic) for musical accuracy
                    val diffCents = abs(1200.0 * log2((detectedPitch / note).toDouble())).toFloat()
                    if (diffCents < minDiff) {
                        minDiff = diffCents
                        closestNote = note
                    }
                }
                
                // Only correct if off by more than 20 cents (gentle)
                val shiftFactor = if (minDiff > 20f) closestNote / detectedPitch else 1.0f
                
                // Apply smooth pitch shift
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
                // Silence or consonant - keep original
                for (j in 0 until window.size) {
                    val globalIndex = i + j
                    if (globalIndex < out.size) out[globalIndex] = window[j]
                }
            }
            i += hopSize
        }
        return out
    }

    private fun detectPitchInWindow(audio: ShortArray): Float {
        if (audio.size < 512) return 0f
        val buffer = FloatArray(audio.size)
        for (i in audio.indices) buffer[i] = audio[i].toFloat() / 32768.0f

        var rms = 0.0f
        for (v in buffer) rms += v * v
        rms = sqrt(rms / buffer.size)
        if (rms < 0.02f) return 0f

        var bestOffset = -1
        var bestCorrelation = 0.0f
        var prevCorrelation = 0.0f
        
        for (offset in 50 until minOf(buffer.size / 2, 800)) {
            var correlation = 0.0f
            val count = minOf(buffer.size - offset, 800)
            for (i in 0 until count) {
                correlation += buffer[i] * buffer[i + offset]
            }
            correlation /= count
            
            if (correlation > bestCorrelation && correlation > 0.2f) {
                if (prevCorrelation > 0 && correlation <= prevCorrelation) {
                    bestCorrelation = correlation
                    bestOffset = offset
                }
            }
            prevCorrelation = correlation
        }
        
        return if (bestOffset > 0) SAMPLE_RATE.toFloat() / bestOffset.toFloat() else 0f
    }

    // ============================================
    // 3. REAL GUITAR SYNTHESIS (Karplus-Strong)
    // This algorithm ACTUALLY sounds like a guitar string
    // ============================================
    private fun karplusStrongPluck(frequency: Float, durationSec: Float, volume: Float): FloatArray {
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val out = FloatArray(totalSamples)
        
        if (frequency <= 0f || frequency > 5000f) return out
        
        // Delay line length = wavelength in samples
        val delayLength = (SAMPLE_RATE / frequency).toInt().coerceAtLeast(2)
        val delayLine = FloatArray(delayLength)
        
        // Initialize with filtered noise (the "pluck")
        var prevNoise = 0f
        for (i in 0 until delayLength) {
            val noise = (Math.random().toFloat() * 2f - 1f)
            // Low-pass the initial noise for warmer sound
            delayLine[i] = (prevNoise + noise) * 0.5f
            prevNoise = noise
        }
        
        var readPos = 0
        
        for (i in 0 until totalSamples) {
            // Output current sample
            out[i] = delayLine[readPos] * volume
            
            // Karplus-Strong update: average of current and next sample (low-pass filter)
            val nextPos = (readPos + 1) % delayLength
            val averaged = (delayLine[readPos] + delayLine[nextPos]) * 0.495f // 0.495 for natural decay
            delayLine[readPos] = averaged
            
            readPos = nextPos
        }
        
        return out
    }

    // ============================================
    // 4. GENERATE GUITAR PROGRESSION
    // ============================================
    private fun generateGuitar(durationSec: Float, rootFreq: Float): ShortArray {
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val out = FloatArray(totalSamples)
        
        val bpm = 90f
        val samplesPerBeat = (60f / bpm * SAMPLE_RATE).toInt()
        val samplesPerBar = samplesPerBeat * 4
        
        // I - V - vi - IV progression (relative to root)
        // Chord frequencies (root position, octave 3-4 range for guitar)
        val rootOctave3 = rootFreq / 2f  // Drop an octave for guitar body
        val progression = listOf(
            // I chord: root, major 3rd, perfect 5th
            listOf(rootOctave3, rootOctave3 * 1.2599f, rootOctave3 * 1.4983f, rootOctave3 * 2f),
            // V chord
            listOf(rootOctave3 * 1.4983f, rootOctave3 * 1.8877f, rootOctave3 * 2.2449f, rootOctave3 * 3f),
            // vi chord (minor)
            listOf(rootOctave3 * 1.6818f, rootOctave3 * 2f, rootOctave3 * 2.3784f, rootOctave3 * 3.3636f),
            // IV chord
            listOf(rootOctave3 * 1.3348f, rootOctave3 * 1.6818f, rootOctave3 * 2f, rootOctave3 * 2.6696f)
        )
        
        // Fingerpicking pattern: pluck each string of the chord in sequence
        // Pattern: 0, 2, 1, 3, 0, 2, 1, 3 (8th notes)
        val pattern = listOf(0, 2, 1, 3, 0, 2, 1, 3)
        val pluckInterval = samplesPerBeat / 2 // 8th notes
        
        var i = 0
        while (i < totalSamples) {
            val barPos = i % samplesPerBar
            val currentBar = (i / samplesPerBar) % 4
            val chord = progression[currentBar]
            
            val patternIndex = (barPos / pluckInterval) % pattern.size
            val stringIndex = pattern[patternIndex]
            val freq = chord[stringIndex % chord.size]
            
            // Pluck duration = time until next pluck
            val pluckDuration = (pluckInterval / SAMPLE_RATE.toFloat()) * 1.5f
            val pluckSamples = (pluckDuration * SAMPLE_RATE).toInt()
            
            // Generate this pluck using Karplus-Strong
            val pluck = karplusStrongPluck(freq, pluckDuration, 0.6f)
            
            // Mix into output
            for (j in pluck.indices) {
                val outIndex = i + j
                if (outIndex < totalSamples) {
                    out[outIndex] += pluck[j]
                }
            }
            
            i += pluckInterval
        }
        
        // Normalize
        var maxVal = 0f
        for (s in out) if (abs(s) > maxVal) maxVal = abs(s)
        if (maxVal == 0f) maxVal = 1f
        
        val finalOut = ShortArray(totalSamples)
        for (i in out.indices) {
            finalOut[i] = ((out[i] / maxVal) * 28000f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return finalOut
    }

    // ============================================
    // 5. STUDIO VOCAL POLISH
    // ============================================
    private fun applyVocalPolish(audio: ShortArray): ShortArray {
        val out = FloatArray(audio.size)
        val echoDelay = (SAMPLE_RATE * 0.32f).toInt()
        
        for (i in audio.indices) {
            val dry = audio[i].toFloat()
            var echoWet = 0.0f
            var reverbWet = 0.0f
            
            if (i >= echoDelay) echoWet = out[i - echoDelay] * 0.28f
            
            val rev1 = (SAMPLE_RATE * 0.037f).toInt()
            val rev2 = (SAMPLE_RATE * 0.059f).toInt()
            val rev3 = (SAMPLE_RATE * 0.089f).toInt()
            
            if (i >= rev1) reverbWet += audio[i - rev1].toFloat() * 0.22f
            if (i >= rev2) reverbWet += audio[i - rev2].toFloat() * 0.18f
            if (i >= rev3) reverbWet += audio[i - rev3].toFloat() * 0.12f
            
            out[i] = (dry * 0.72f) + echoWet + reverbWet
        }
        
        val finalOut = ShortArray(audio.size)
        for (i in out.indices) finalOut[i] = out[i].toInt().coerceIn(-32768, 32767).toShort()
        return finalOut
    }

    // ============================================
    // 6. MASTER PROCESS & MIX
    // ============================================
    fun processAndMix(rawVocal: ShortArray): ShortArray {
        val rootKey = detectRootKey(rawVocal)
        val autoTuned = applyAutoTune(rawVocal, rootKey)
        
        val durationSec = rawVocal.size / SAMPLE_RATE.toFloat()
        val guitar = generateGuitar(durationSec, rootKey)
        
        val polishedVocal = applyVocalPolish(autoTuned)
        
        val finalSize = maxOf(polishedVocal.size, guitar.size)
        val finalMix = ShortArray(finalSize)
        
        for (i in 0 until finalSize) {
            val vocalSample = if (i < polishedVocal.size) polishedVocal[i].toInt() else 0
            val guitarSample = if (i < guitar.size) guitar[i].toInt() else 0
            
            // Vocal forward, guitar supportive
            val mixed = ((vocalSample * 0.85f) + (guitarSample * 0.55f)).toInt()
            finalMix[i] = mixed.coerceIn(-32768, 32767).toShort()
        }
        
        return finalMix
    }
}
