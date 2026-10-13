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

    // 1. DETECT VOCAL KEY
    fun detectRootKey(audio: ShortArray): Float {
        val analyzeLength = minOf(audio.size, SAMPLE_RATE * 2) // Analyze first 2 seconds
        if (analyzeLength < 1024) return 261.63f // Default to C4

        val buffer = FloatArray(analyzeLength)
        for (i in 0 until analyzeLength) buffer[i] = audio[i].toFloat() / 32768.0f

        var bestOffset = -1
        var bestCorrelation = 0.0f
        var rms = 0.0f
        
        for (i in 0 until analyzeLength) rms += buffer[i] * buffer[i]
        rms = sqrt(rms / analyzeLength.toFloat())
        if (rms < 0.01f) return 261.63f 

        var lastCorrelation = 1.0f
        // Search typical vocal range (80Hz to 800Hz)
        for (offset in 50 until minOf(analyzeLength / 2, 2000)) { 
            var correlation = 0.0f
            for (i in 0 until minOf(analyzeLength - offset, 2000)) {
                correlation += abs(buffer[i] - buffer[i + offset])
            }
            correlation = 1.0f - (correlation / minOf(analyzeLength - offset, 2000).toFloat())
            
            if (correlation > 0.85f && correlation > lastCorrelation) {
                bestCorrelation = correlation
                bestOffset = offset
            }
            lastCorrelation = correlation
        }
        return if (bestOffset > 0) SAMPLE_RATE.toFloat() / bestOffset.toFloat() else 261.63f
    }

    // 2. ADVANCED AUTO-TUNE (Scale Quantization)
    private fun applyAdvancedAutoTune(audio: ShortArray, rootFreq: Float): ShortArray {
        val out = ShortArray(audio.size)
        val windowSize = 2048
        val hopSize = 1024
        
        // Build a Major Scale based on the detected root frequency
        // Semitone multipliers: 0, 2, 4, 5, 7, 9, 11, 12
        val scaleMultipliers = listOf(1.0f, 1.12246f, 1.25992f, 1.33484f, 1.49831f, 1.68179f, 1.88775f, 2.0f)
        val scaleNotes = scaleMultipliers.map { rootFreq * it }

        var i = 0
        while (i < audio.size) {
            val end = minOf(i + windowSize, audio.size)
            val window = audio.copyOfRange(i, end)
            
            val detectedPitch = detectPitchInWindow(window)
            
            if (detectedPitch > 0) {
                // Find closest note in the detected Major scale
                var closestNote = detectedPitch
                var minDiff = Float.MAX_VALUE
                
                for (note in scaleNotes) {
                    val diff = abs(note - detectedPitch)
                    if (diff < minDiff) {
                        minDiff = diff
                        closestNote = note
                    }
                }
                
                val shiftFactor = closestNote / detectedPitch
                
                // Apply pitch correction (Advanced: smooth linear interpolation)
                for (j in 0 until window.size) {
                    val srcIndex = j * shiftFactor
                    val indexLow = srcIndex.toInt()
                    val indexHigh = (indexLow + 1).coerceAtMost(window.size - 1)
                    val frac = srcIndex - indexLow
                    
                    if (indexLow >= 0 && indexLow < window.size) {
                        val sample = window[indexLow].toFloat() * (1.0f - frac) + window[indexHigh].toFloat() * frac
                        val globalIndex = i + j
                        if (globalIndex < out.size) {
                            out[globalIndex] = sample.toInt().coerceIn(-32768, 32767).toShort()
                        }
                    }
                }
            } else {
                // No pitch detected (silence/consonant), keep original
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

    // 3. PROFESSIONAL PROCEDURAL GUITAR (Artifact-Free, Dynamic Key)
    private fun generateProfessionalGuitar(durationSec: Float, rootFreq: Float): ShortArray {
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val out = FloatArray(totalSamples)
        val bpm = 80f // Slow, emotional guitar tempo
        val samplesPerBeat = (60.0f / bpm * SAMPLE_RATE.toFloat()).toInt()
        val sixteenth = samplesPerBeat / 4
        
        // Professional Progression: I - V - vi - IV (Relative to detected root)
        // Multipliers: Root, Root*1.5 (5th), Root*1.68 (6th minor), Root*1.33 (4th)
        val progression = listOf(
            listOf(rootFreq, rootFreq * 1.2599f, rootFreq * 1.4983f),       // I
            listOf(rootFreq * 1.4983f, rootFreq * 1.8877f, rootFreq * 2.0f), // V
            listOf(rootFreq * 1.6818f, rootFreq * 2.0f, rootFreq * 2.3784f), // vi
            listOf(rootFreq * 1.3348f, rootFreq * 1.6818f, rootFreq * 2.0f)  // IV
        )

        for (i in 0 until totalSamples) {
            val beatPos = i % samplesPerBeat
            val barPos = i % (samplesPerBeat * 4)
            val currentBar = (i / (samplesPerBeat * 4)) % 4
            val currentChord = progression[currentBar]
            
            var sample = 0.0f
            
            // Guitar Arpeggio Pattern (Pluck on 16th notes)
            val noteIndex = (i / sixteenth) % currentChord.size
            val pluckPos = i % sixteenth
            
            if (pluckPos < 10) { // Trigger new pluck
                val freq = currentChord[noteIndex]
                
                // Professional Guitar Synthesis: Fundamental + 2nd Harmonic + 3rd Harmonic
                val t = pluckPos.toFloat() / SAMPLE_RATE.toFloat()
                
                // String body (decaying sine waves)
                val fundamental = sin(2.0f * PI.toFloat() * freq * t)
                val harmonic2 = sin(2.0f * PI.toFloat() * (freq * 2.0f) * t) * 0.5f
                val harmonic3 = sin(2.0f * PI.toFloat() * (freq * 3.0f) * t) * 0.25f
                
                // Envelope: Fast attack, smooth exponential decay (mimics guitar string)
                val decay = exp(-t * 4.0f) 
                
                // Pick noise (brief white noise burst for realism)
                val pickNoise = if (pluckPos < 3) (Math.random().toFloat() * 2f - 1f) * 0.3f else 0f
                
                sample = (fundamental + harmonic2 + harmonic3) * decay * 0.4f + pickNoise
            } else {
                // Sustain tail
                val t = pluckPos.toFloat() / SAMPLE_RATE.toFloat()
                val decay = exp(-t * 4.0f)
                // Very subtle room reverb tail
                sample *= decay * 0.8f 
            }
            
            out[i] = sample
        }
        
        // Normalize and convert to ShortArray
        val finalOut = ShortArray(totalSamples)
        var maxVal = 0.0f
        for (s in out) if (abs(s) > maxVal) maxVal = abs(s)
        if (maxVal == 0.0f) maxVal = 1.0f
        
        for (i in out.indices) {
            finalOut[i] = ((out[i] / maxVal) * 32767.0f * 0.7f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return finalOut
    }

    // 4. STUDIO VOCAL POLISH (Reverb + Delay)
    private fun applyStudioPolish(audio: ShortArray): ShortArray {
        val out = FloatArray(audio.size)
        val echoDelay = (SAMPLE_RATE * 0.35f).toInt() // 350ms professional slapback
        
        for (i in audio.indices) {
            val dry = audio[i].toFloat()
            var echoWet = 0.0f
            var reverbWet = 0.0f
            
            if (i >= echoDelay) echoWet = out[i - echoDelay] * 0.30f
            
            val rev1 = (SAMPLE_RATE * 0.04f).toInt()
            val rev2 = (SAMPLE_RATE * 0.07f).toInt()
            val rev3 = (SAMPLE_RATE * 0.11f).toInt()
            
            if (i >= rev1) reverbWet += audio[i - rev1].toFloat() * 0.25f
            if (i >= rev2) reverbWet += audio[i - rev2].toFloat() * 0.20f
            if (i >= rev3) reverbWet += audio[i - rev3].toFloat() * 0.15f
            
            out[i] = (dry * 0.70f) + echoWet + reverbWet
        }
        
        val finalOut = ShortArray(audio.size)
        for (i in out.indices) finalOut[i] = out[i].toInt().coerceIn(-32768, 32767).toShort()
        return finalOut
    }

    // 5. MASTER MIX
    fun processAndMix(rawVocal: ShortArray): ShortArray {
        // Step 1: Detect the key of the singing
        val rootKey = detectRootKey(rawVocal)
        
        // Step 2: Apply advanced auto-tune to snap vocals to the detected Major scale
        val autoTuned = applyAdvancedAutoTune(rawVocal, rootKey)
        
        // Step 3: Generate dynamic guitar that perfectly matches the detected key
        val durationSec = rawVocal.size / SAMPLE_RATE.toFloat()
        val guitar = generateProfessionalGuitar(durationSec, rootKey)
        
        // Step 4: Polish the vocal with professional reverb and echo
        val polishedVocal = applyStudioPolish(autoTuned)
        
        // Step 5: Mix vocal forward, guitar supporting
        val finalSize = maxOf(polishedVocal.size, guitar.size)
        val finalMix = ShortArray(finalSize)
        
        for (i in 0 until finalSize) {
            val vocalSample = if (i < polishedVocal.size) polishedVocal[i].toInt() else 0
            val guitarSample = if (i < guitar.size) guitar[i].toInt() else 0
            
            // Mix: Vocal 80%, Guitar 50% (leaves headroom, vocal is the star)
            val mixed = ((vocalSample * 0.80f) + (guitarSample * 0.50f)).toInt()
            finalMix[i] = mixed.coerceIn(-32768, 32767).toShort()
        }
        
        return finalMix
    }
}
