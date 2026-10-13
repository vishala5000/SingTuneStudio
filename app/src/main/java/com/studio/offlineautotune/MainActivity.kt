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
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    var statusText by remember { mutableStateOf("Tap the mic to Record your Vocals") }
    var recordedAudio by remember { mutableStateOf<ShortArray?>(null) }
    var selectedPreset by remember { mutableStateOf("Pop") }
    var detectedKey by remember { mutableStateOf("A") }
    var detectedBPM by remember { mutableStateOf(120) }
    var waveform by remember { mutableStateOf<List<Float>>(emptyList()) }
    
    val isRecordingAtomic = remember { AtomicBoolean(false) }
    val audioBufferForVisualization = remember { mutableStateListOf<Short>() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isRecording = true
            isRecordingAtomic.set(true)
            statusText = "Recording... Tap to Stop"
            runRecordingProcess(scope, isRecordingAtomic, context, audioBufferForVisualization) { processing, status, audio, done, key, bpm ->
                isProcessing = processing
                statusText = status
                if (audio != null) recordedAudio = audio
                if (done) {
                    isRecording = false
                    detectedKey = key
                    detectedBPM = bpm
                }
            }
        } else {
            statusText = "Mic permission denied"
        }
    }

    // Real-time waveform update
    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isRecording) {
                if (audioBufferForVisualization.size > 1000) {
                    waveform = AudioEngine.getWaveformData(
                        audioBufferForVisualization.takeLast(4410).toShortArray(),
                        100
                    )
                }
                delay(100)
            }
        } else {
            waveform = emptyList()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("SingTune Studio", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Advanced Offline Auto-Tune & Beat Maker", fontSize = 14.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(20.dp))

            // Waveform Visualization
            if (waveform.isNotEmpty()) {
                WaveformVisualizer(waveform, modifier = Modifier.height(80.dp).fillMaxWidth())
                Spacer(modifier = Modifier.height(20.dp))
            }

            // Record Button
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
                                audioBufferForVisualization.clear()
                                statusText = "Recording... Tap to Stop"
                                runRecordingProcess(scope, isRecordingAtomic, context, audioBufferForVisualization) { processing, status, audio, done, key, bpm ->
                                    isProcessing = processing
                                    statusText = status
                                    if (audio != null) recordedAudio = audio
                                    if (done) {
                                        isRecording = false
                                        detectedKey = key
                                        detectedBPM = bpm
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

            Spacer(modifier = Modifier.height(20.dp))
            Text(statusText, color = Color.White, fontSize = 16.sp)

            // Key & BPM Display
            if (!isRecording && !isProcessing) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    InfoChip("Key: $detectedKey", Color(0xFF03DAC5))
                    InfoChip("BPM: $detectedBPM", Color(0xFF03DAC5))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Preset Selection
            if (!isRecording && !isProcessing) {
                Text("Effect Preset:", color = Color.White, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AudioEngine.PRESETS.keys.forEach { preset ->
                        PresetButton(
                            name = preset,
                            isSelected = preset == selectedPreset,
                            onClick = {
                                selectedPreset = preset
                                AudioEngine.setPreset(preset)
                            }
                        )
                    }
                }
            }

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

@Composable
fun WaveformVisualizer(waveform: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val path = Path()
        val width = size.width
        val height = size.height
        val pointSpacing = width / waveform.size
        
        path.moveTo(0f, height / 2)
        
        waveform.forEachIndexed { index, amplitude ->
            val x = index * pointSpacing
            val y = height / 2 - (amplitude * height / 2)
            path.lineTo(x, y)
        }
        
        drawPath(
            path = path,
            color = Color(0xFF03DAC5),
            style = Stroke(width = 2f)
        )
    }
}

@Composable
fun InfoChip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.2f),
        modifier = Modifier.padding(4.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun PresetButton(name: String, isSelected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) Color(0xFF03DAC5) else Color(0xFF2C2C2C)
        ),
        modifier = Modifier.padding(4.dp)
    ) {
        Text(name, color = if (isSelected) Color.Black else Color.White)
    }
}

private fun runRecordingProcess(
    scope: kotlinx.coroutines.CoroutineScope,
    isRecordingAtomic: AtomicBoolean,
    context: android.content.Context,
    audioBufferForVisualization: MutableList<Short>,
    onResult: (isProcessing: Boolean, status: String, audio: ShortArray?, done: Boolean, key: String, bpm: Int) -> Unit
) {
    scope.launch(Dispatchers.IO) {
        val audioRecord = AudioEngine.createAudioRecord()
        audioRecord.startRecording()
        val audioBuffer = mutableListOf<Short>()
        val buffer = ShortArray(1024)

        while (isRecordingAtomic.get()) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read > 0) {
                for (i in 0 until read) {
                    audioBuffer.add(buffer[i])
                    audioBufferForVisualization.add(buffer[i])
                }
                // Keep visualization buffer manageable
                if (audioBufferForVisualization.size > 44100) {
                    audioBufferForVisualization.removeAt(0)
                }
            }
        }

        audioRecord.stop()
        audioRecord.release()
        val rawAudio = audioBuffer.toShortArray()

        if (rawAudio.isEmpty()) {
            withContext(Dispatchers.Main) { 
                onResult(false, "Recording was empty. Try again.", null, true, "A", 120) 
            }
            return@launch
        }

        withContext(Dispatchers.Main) { 
            onResult(true, "Processing your studio track...", null, false, "A", 120) 
        }

        // Detect key and BPM
        val (key, bpm) = AudioEngine.detectKeyAndBPM(rawAudio)

        val beat = withContext(Dispatchers.Default) { 
            AudioEngine.generateProceduralBeat(context, rawAudio.size / AudioEngine.SAMPLE_RATE.toFloat(), rawAudio) 
        }
        
        val finalMix = withContext(Dispatchers.Default) { 
            AudioEngine.processVocalAndMix(rawAudio, beat) 
        }
        
        val fileName = "SingTune_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"
        FileSaver.saveAsWav(context, finalMix, fileName)

        withContext(Dispatchers.Main) { 
            onResult(false, "Saved to Music/SingTuneStudio!", finalMix, true, key, bpm)
            Toast.makeText(context, "Song saved! Key: $key, BPM: $bpm", Toast.LENGTH_LONG).show()
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
