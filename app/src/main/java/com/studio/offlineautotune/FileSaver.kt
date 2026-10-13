package com.studio.offlineautotune

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.nio.ByteBuffer
import java.nio.ByteOrder

object FileSaver {
    fun saveAsWav(context: Context, audioData: ShortArray, fileName: String): String? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$fileName.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/SingTuneStudio")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), 
            contentValues
        )
        
        uri?.let {
            resolver.openOutputStream(it)?.use { os ->
                writeWavHeader(os, audioData.size, AudioEngine.SAMPLE_RATE, 1, 16)
                val buffer = ByteBuffer.allocate(audioData.size * 2)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                for (sample in audioData) {
                    buffer.putShort(sample)
                }
                os.write(buffer.array())
            }
            
            contentValues.clear()
            contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(it, contentValues, null, null)
            return uri.toString()
        }
        return null
    }

    private fun writeWavHeader(
        out: java.io.OutputStream, 
        totalAudioLen: Int, 
        sampleRate: Int, 
        channels: Int, 
        bitDepth: Int
    ) {
        val byteRate = bitDepth * sampleRate * channels / 8
        val blockAlign = channels * bitDepth / 8
        val totalDataLen = totalAudioLen * 2 + 36

        val header = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            totalDataLen.toByte(), (totalDataLen shr 8).toByte(),
            (totalDataLen shr 16).toByte(), (totalDataLen shr 24).toByte(),
            'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
            'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(),
            16, 0, 0, 0, 
            1, 0, 
            channels.toByte(), 0,
            sampleRate.toByte(), (sampleRate shr 8).toByte(),
            (sampleRate shr 16).toByte(), (sampleRate shr 24).toByte(),
            byteRate.toByte(), (byteRate shr 8).toByte(),
            (byteRate shr 16).toByte(), (byteRate shr 24).toByte(),
            blockAlign.toByte(), 0,
            bitDepth.toByte(), 0,
            'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
            (totalAudioLen * 2).toByte(), ((totalAudioLen * 2) shr 8).toByte(),
            ((totalAudioLen * 2) shr 16).toByte(), ((totalAudioLen * 2) shr 24).toByte()
        )
        out.write(header, 0, header.size)
    }
}
