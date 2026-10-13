package com.studio.offlineautotune

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StudioApp() }
    }
}

@Composable
fun StudioApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var isRecording by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Tap to Record") }
    var recordedAudio by remember { mutableStateOf<ShortArray?>(null) }
    var detectedKey by remember { mutableStateOf("A") }
    
    val isRecordingAtomic = remember { AtomicBoolean(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isRecording = true
            isRecordingAtomic.set(true)
            statusText = "Recording... Tap to Stop"
            runRecordingProcess(scope, isRecordingAtomic, context) { processing, status, audio, done, key ->
                isProcessing = processing
                statusText = status
                if (audio != null) recordedAudio = audio
                if (done) {
                    isRecording = false
                    detectedKey = key
                }
            }
        } else {
            statusText = "Mic permission denied"
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("SingTune Studio", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Auto-Tune • Reverb • Echo • Beat", fontSize = 14.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(60.dp))

            Box(
                modifier = Modifier
                    .size(180.dp)
                    .background(
                        when {
                            isProcessing -> Color(0xFF6200EE)
                            isRecording -> Color.Red
                            else -> Color(0xFFBB86FC)
                        },
                        CircleShape
                    )
                    .clickable {
                        if (isProcessing) return@clickable
                        if (!isRecording) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                isRecording = true
                                isRecordingAtomic.set(true)
                                statusText = "Recording... Tap to Stop"
                                runRecordingProcess(scope, isRecordingAtomic, context) { processing, status, audio, done, key ->
                                    isProcessing = processing
                                    statusText = status
                                    if (audio != null) recordedAudio = audio
                                    if (done) {
                                        isRecording = false
                                        detectedKey = key
                                    }
                                }
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            isRecordingAtomic.set(false)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                when {
                    isProcessing -> CircularProgressIndicator(color = Color.White)
                    isRecording -> Text("STOP", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    else -> Text("REC", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
            Text(statusText, color = Color.White, fontSize = 16.sp)

            if (!isRecording && !isProcessing) {
                Spacer(modifier = Modifier.height(20.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF03DAC5).copy(alpha = 0.2f)
                ) {
                    Text(
                        "Key: $detectedKey • BPM: 120",
                        color = Color(0xFF03DAC5),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(30.dp))

            if (recordedAudio != null && !isRecording && !isProcessing) {
                Button(
                    onClick = { playAudio(recordedAudio!!) }, 
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF03DAC5))
                ) {
                    Text("Play Final Mix", color = Color.Black)
                }
            }
        }
    }
}

private fun runRecordingProcess(
    scope: kotlinx.coroutines.CoroutineScope,
    isRecordingAtomic: AtomicBoolean,
    context: android.content.Context,
    onResult: (isProcessing: Boolean, status: String, audio: ShortArray?, done: Boolean, key: String) -> Unit
) {
    scope.launch(Dispatchers.IO) {
        val audioRecord = AudioEngine.createAudioRecord()
        audioRecord.startRecording()
        val audioBuffer = mutableListOf<Short>()
        val buffer = ShortArray(1024)

        while (isRecordingAtomic.get()) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read > 0) for (i in 0 until read) audioBuffer.add(buffer[i])
        }

        audioRecord.stop()
        audioRecord.release()
        val rawAudio = audioBuffer.toShortArray()

        if (rawAudio.isEmpty()) {
            withContext(Dispatchers.Main) { 
                onResult(false, "Recording empty. Try again.", null, true, "A") 
            }
            return@launch
        }

        withContext(Dispatchers.Main) { 
            onResult(true, "Processing...", null, false, "A") 
        }

        val (key, _) = AudioEngine.detectKeyAndBPM(rawAudio)

        val beat = withContext(Dispatchers.Default) { 
            AudioEngine.generateProceduralBeat(context, rawAudio.size / AudioEngine.SAMPLE_RATE.toFloat(), rawAudio) 
        }
        
        val finalMix = withContext(Dispatchers.Default) { 
            AudioEngine.processVocalAndMix(rawAudio, beat) 
        }
        
        val fileName = "SingTune_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"
        FileSaver.saveAsWav(context, finalMix, fileName)

        withContext(Dispatchers.Main) { 
            onResult(false, "Saved! Key: $key", finalMix, true, key)
            Toast.makeText(context, "Song saved!", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun playAudio(audio: ShortArray) {
    val audioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(AudioEngine.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(audio.size * 2)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
    audioTrack.write(audio, 0, audio.size)
    audioTrack.play()
}
