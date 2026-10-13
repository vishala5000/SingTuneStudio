package com.studio.offlineautotune

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    // Load WAV file from raw resources (skips 44-byte WAV header)
    private fun loadAudioFromRaw(context: Context, resourceId: Int): ShortArray {
        val inputStream = context.resources.openRawResource(resourceId)
        val byteArrayOutputStream = ByteArrayOutputStream()
        
        val buffer = ByteArray(1024)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            byteArrayOutputStream.write(buffer, 0, bytesRead)
        }
        
        val bytes = byteArrayOutputStream.toByteArray()
        // Skip the 44-byte WAV header to get raw PCM data
        val pcmBytes = bytes.copyOfRange(44, bytes.size)
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val audioData = ShortArray(shortBuffer.remaining())
        shortBuffer.get(audioData)
        
        return audioData
    }

    // Loop audio seamlessly to match vocal duration
    private fun loopAudio(audio: ShortArray, durationSec: Float): ShortArray {
        val targetSamples = (SAMPLE_RATE * durationSec).toInt()
        val looped = ShortArray(targetSamples)
        
        for (i in 0 until targetSamples) {
            looped[i] = audio[i % audio.size]
        }
        return looped
    }

    // Generate beat using REAL professional audio loops
    fun generateProceduralBeat(context: Context, durationSec: Float, vocalAudio: ShortArray): ShortArray {
        val drumLoop = loadAudioFromRaw(context, R.raw.drum_loop)
        val bassLoop = loadAudioFromRaw(context, R.raw.bass_loop)
        val chordLoop = loadAudioFromRaw(context, R.raw.chord_loop)
        val arpLoop = loadAudioFromRaw(context, R.raw.arp_loop)
        
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
            
            // Professional mix levels (Drums loudest, arp subtle)
            val mixed = (drumSample * 0.9f + 
                        bassSample * 0.7f + 
                        chordSample * 0.4f + 
                        arpSample * 0.3f).toInt()
            
            beat[i] = mixed.coerceIn(-32768, 32767).toShort()
        }
        return beat
    }

    // --- VOCAL PROCESSING ---
    private fun removeMud(audio: ShortArray): ShortArray {
        val out = ShortArray(audio.size)
        val rc = 1.0f / (2.0f * PI.toFloat() * 60.0f)
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

    private fun addClarity(audio: ShortArray): ShortArray {
        val out = ShortArray(audio.size)
        var prev = 0.0f
        for (i in audio.indices) {
            val current = audio[i].toFloat()
            val diff = current - prev
            prev = current
            val boosted = current + (diff * 1.5f)
            out[i] = boosted.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun applyCompression(audio: ShortArray): ShortArray {
        val out = ShortArray(audio.size)
        val threshold = 12000f
        val ratio = 4.0f
        for (i in audio.indices) {
            val sample = audio[i].toFloat()
            val absSample = abs(sample)
            val outSample = if (absSample > threshold) threshold + (absSample - threshold) / ratio else absSample
            val finalSample = if (sample < 0) -outSample else outSample
            out[i] = (finalSample * 1.15f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun applyStudioEffects(audio: ShortArray): ShortArray {
        val out = FloatArray(audio.size)
        val echoDelay = (SAMPLE_RATE * 0.3f).toInt()
        for (i in audio.indices) {
            val dry = audio[i].toFloat()
            var echoWet = 0.0f
            var reverbWet = 0.0f
            if (i >= echoDelay) echoWet = out[i - echoDelay] * 0.35f
            val rev1 = (SAMPLE_RATE * 0.04f).toInt()
            val rev2 = (SAMPLE_RATE * 0.07f).toInt()
            val rev3 = (SAMPLE_RATE * 0.11f).toInt()
            if (i >= rev1) reverbWet += audio[i - rev1].toFloat() * 0.25f
            if (i >= rev2) reverbWet += audio[i - rev2].toFloat() * 0.20f
            if (i >= rev3) reverbWet += audio[i - rev3].toFloat() * 0.15f
            out[i] = (dry * 0.65f) + echoWet + reverbWet
        }
        val finalOut = ShortArray(audio.size)
        for (i in out.indices) finalOut[i] = out[i].toInt().coerceIn(-32768, 32767).toShort()
        return finalOut
    }

    fun processVocalAndMix(rawVocal: ShortArray, beat: ShortArray): ShortArray {
        val clean = removeMud(rawVocal)
        val clear = addClarity(clean)
        val compressed = applyCompression(clear)
        val effected = applyStudioEffects(compressed)
        val finalSize = maxOf(effected.size, beat.size)
        val finalMix = ShortArray(finalSize)
        for (i in 0 until finalSize) {
            val vocalSample = if (i < effected.size) effected[i].toInt() else 0
            val beatSample = if (i < beat.size) beat[i].toInt() else 0
            val mixed = ((vocalSample * 0.85f) + (beatSample * 0.40f)).toInt()
            finalMix[i] = mixed.coerceIn(-32768, 32767).toShort()
        }
        return finalMix
    }
}
