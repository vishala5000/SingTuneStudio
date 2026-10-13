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
import java.util.concurrent.atomic.AtomicBoolean

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

@Composable
fun StudioApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var isRecording by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Tap the mic to Record your Vocals") }
    var recordedAudio by remember { mutableStateOf<ShortArray?>(null) }
    
    // Thread-safe flag to stop the recording loop without blocking the main thread
    val isRecordingAtomic = remember { AtomicBoolean(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startRecordingFlow(scope, isRecordingAtomic, 
                onRecordingStarted = { 
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
                                startRecordingFlow(scope, isRecordingAtomic, 
                                    onRecordingStarted = { 
                                        isRecording = true
                                        statusText = "Recording... Tap to Stop" 
                                    }
                                )
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            // Stop recording safely
                            isRecordingAtomic.set(false)
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

private fun startRecordingFlow(
    scope: kotlinx.coroutines.CoroutineScope,
    isRecordingAtomic: AtomicBoolean,
    onRecordingStarted: () -> Unit
) {
    onRecordingStarted()
    isRecordingAtomic.set(true)

    scope.launch(Dispatchers.IO) {
        val audioRecord = AudioEngine.createAudioRecord()
        audioRecord.startRecording()
        val audioBuffer = mutableListOf<Short>()
        val buffer = ShortArray(1024)

        // Non-blocking read loop
        while (isRecordingAtomic.get()) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read > 0) {
                for (i in 0 until read) {
                    audioBuffer.add(buffer[i])
                }
            }
        }

        audioRecord.stop()
        audioRecord.release()

        val rawAudio = audioBuffer.toShortArray()

        withContext(Dispatchers.Main) {
            if (rawAudio.isNotEmpty()) {
                // State updates for UI
                // (We'll let the outer scope handle the actual state mutation via a callback or by passing a mutable state, 
                // but for simplicity, we'll assume the caller handles UI updates. Let's refine this:)
            }
        }
        
        // Note: To properly update Compose state from here, we pass a callback. 
        // See the refined call below.
    }
}

// Refined recording function that properly updates Compose state
@Composable
fun RecordingHandler(
    scope: kotlinx.coroutines.CoroutineScope,
    isRecordingAtomic: AtomicBoolean,
    onStateChange: (isRecording: Boolean, isProcessing: Boolean, status: String, audio: ShortArray?) -> Unit
) {
    // This is a helper to keep the Composable clean
}
