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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                StudioApp()
            }
        }
    }
}

// Temporary holder for recorded audio buffer
private var globalRecordedAudio: ShortArray? = null

@Composable
fun StudioApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var isRecording by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Tap the mic to Record your Vocals") }
    var recordedAudio by remember { mutableStateOf<ShortArray?>(null) }
    var audioRecord by remember { mutableStateOf<android.media.AudioRecord?>(null) }
    var recordThread by remember { mutableStateOf<Thread?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startRecordingLogic(
                onRecordingStarted = { rec, thread ->
                    audioRecord = rec
                    recordThread = thread
                    isRecording = true
                    statusText = "Recording... Tap to Stop"
                }
            )
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
            Text("100% Offline Auto-Tune & Beat Maker", fontSize = 14.sp, color = Color.Gray)
            
            Spacer(modifier = Modifier.height(60.dp))

            Box(
                modifier = Modifier
                    .size(180.dp)
                    .background(if (isRecording) Color.Red else Color(0xFFBB86FC), CircleShape)
                    .clickable {
                        if (isProcessing) return@clickable
                        
                        if (!isRecording) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                startRecordingLogic(
                                    onRecordingStarted = { rec, thread ->
                                        audioRecord = rec
                                        recordThread = thread
                                        isRecording = true
                                        statusText = "Recording... Tap to Stop"
                                    }
                                )
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            // Stop recording and process
                            audioRecord?.stop()
                            recordThread?.join()
                            audioRecord?.release()
                            isRecording = false
                            statusText = "Processing your studio track..."
                            isProcessing = true
                            
                            scope.launch {
                                val rawAudio = globalRecordedAudio
                                if (rawAudio != null && rawAudio.isNotEmpty()) {
                                    val beat = withContext(Dispatchers.Default) {
                                        AudioEngine.generateProceduralBeat(rawAudio.size / AudioEngine.SAMPLE_RATE.toFloat())
                                    }
                                    val finalMix = withContext(Dispatchers.Default) {
                                        AudioEngine.processVocalAndMix(rawAudio, beat)
                                    }
                                    recordedAudio = finalMix
                                    
                                    val fileName = "SingTune_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"
                                    withContext(Dispatchers.IO) {
                                        FileSaver.saveAsWav(context, finalMix, fileName)
                                    }
                                    
                                    isProcessing = false
                                    statusText = "Saved to Music/SingTuneStudio!"
                                    Toast.makeText(context, "Song saved!", Toast.LENGTH_SHORT).show()
                                } else {
                                    isProcessing = false
                                    statusText = "Recording was empty. Try again."
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    Text(
                        if (isRecording) "STOP" else "REC", 
                        color = Color.White, 
                        fontSize = 28.sp, 
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
            Text(statusText, color = Color.White, fontSize = 16.sp)

            Spacer(modifier = Modifier.height(20.dp))

            if (recordedAudio != null && !isRecording && !isProcessing) {
                Button(
                    onClick = { playAudio(recordedAudio!!) }, 
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF03DAC5))
                ) {
                    Text("Play Final Studio Mix", color = Color.Black)
                }
            }
        }
    }
}

fun startRecordingLogic(
    onRecordingStarted: (android.media.AudioRecord, Thread) -> Unit
) {
    val audioRecord = AudioEngine.createAudioRecord()
    audioRecord.startRecording()
    
    val audioBuffer = mutableListOf<Short>()
    
    val thread = Thread {
        val buffer = ShortArray(1024)
        while (audioRecord.recordingState == android.media.AudioRecord.RECORDSTATE_RECORDING) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read > 0) {
                for (i in 0 until read) {
                    audioBuffer.add(buffer[i])
                }
            }
        }
        globalRecordedAudio = audioBuffer.toShortArray()
    }
    thread.start()
    
    onRecordingStarted(audioRecord, thread)
}

fun playAudio(audio: ShortArray) {
    val audioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioEngine.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(audio.size * 2)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
    
    audioTrack.write(audio, 0, audio.size)
    audioTrack.play()
}
