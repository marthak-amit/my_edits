package com.myedits.app.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.myedits.app.ui.common.PrimaryButton
import com.myedits.app.ui.common.formatTime
import com.myedits.app.ui.theme.EditsColors
import kotlinx.coroutines.delay
import java.io.File

private fun has(ctx: android.content.Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

/** In-app camera: record clips straight into the project. */
@Composable
fun CameraScreen(onClose: () -> Unit, onRecorded: (File) -> Unit) {
    val ctx = LocalContext.current
    var cameraOk by remember { mutableStateOf(has(ctx, Manifest.permission.CAMERA)) }
    var micOk by remember { mutableStateOf(has(ctx, Manifest.permission.RECORD_AUDIO)) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        cameraOk = r[Manifest.permission.CAMERA] == true || has(ctx, Manifest.permission.CAMERA)
        micOk = r[Manifest.permission.RECORD_AUDIO] == true || has(ctx, Manifest.permission.RECORD_AUDIO)
        asked = true
    }
    LaunchedEffect(Unit) { if (!cameraOk) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("camera_screen")) {
        if (cameraOk) CameraContent(micOk, onClose, onRecorded)
        else Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Camera access needed", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("Allow camera and microphone access to record clips inside My Edits.", color = EditsColors.TextDim, textAlign = TextAlign.Center)
            PrimaryButton("Allow access", { launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }, tag = "camera_allow")
            TextButton(onClick = onClose) { Text("Not now", color = EditsColors.TextDim) }
        }
    }
}

@Composable
private fun CameraContent(mic: Boolean, onClose: () -> Unit, onRecorded: (File) -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var lens by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var torch by remember { mutableStateOf(false) }
    var timerSec by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var finishing by remember { mutableStateOf(false) }
    // Leaving the screen while recording discards the take instead of silently adding it to the project.
    val discard = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    DisposableEffect(lens) {
        val future = ProcessCameraProvider.getInstance(ctx)
        val executor = ContextCompat.getMainExecutor(ctx)
        future.addListener({
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.Builder().requireLensFacing(lens).build(), preview, vc)
                videoCapture = vc
            }.onFailure { Toast.makeText(ctx, "Camera unavailable", Toast.LENGTH_SHORT).show(); onClose() }
        }, executor)
        onDispose { runCatching { future.get().unbindAll() } }
    }
    LaunchedEffect(torch, camera) { runCatching { camera?.cameraControl?.enableTorch(torch) } }

    LaunchedEffect(recording) {
        elapsed = 0
        while (recording != null) { delay(100); elapsed += 100 }
    }
    DisposableEffect(Unit) { onDispose { discard.set(true); runCatching { recording?.stop() } } }

    fun startRecording() {
        val vc = videoCapture ?: return
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "rec_${System.currentTimeMillis()}.mp4")
        val pending = vc.output.prepareRecording(ctx, FileOutputOptions.Builder(file).build()).let {
            if (mic) it.withAudioEnabled() else it
        }
        recording = pending.start(ContextCompat.getMainExecutor(ctx)) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                recording = null
                if (discard.get()) { file.delete(); return@start }
                if (!ev.hasError() && file.length() > 0) { finishing = true; onRecorded(file) }
                else { file.delete(); if (ev.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) Toast.makeText(ctx, "Recording failed", Toast.LENGTH_SHORT).show() else Toast.makeText(ctx, "Recording was too short", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    LaunchedEffect(countdown) {
        if (countdown > 0) { delay(1000); countdown -= 1; if (countdown == 0) startRecording() }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize())

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Filled.Close, "Close", onClose, "camera_close", enabled = recording == null)
            if (recording != null) {
                Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(EditsColors.Red)); Spacer(Modifier.width(8.dp))
                    Text(formatTime(elapsed), color = Color.White, modifier = Modifier.testTag("camera_elapsed"))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundIcon(Icons.Filled.FlipCameraAndroid, "Flip", { lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK }, "camera_flip", enabled = recording == null)
                RoundIcon(if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff, "Flash", { torch = !torch }, "camera_torch")
                RoundIcon(Icons.Filled.Timer, if (timerSec == 0) "Timer off" else "${timerSec}s", { timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 } }, "camera_timer", enabled = recording == null, badge = if (timerSec > 0) "${timerSec}s" else null)
            }
        }

        if (countdown > 0) Text("$countdown", color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center).testTag("camera_countdown"))

        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 32.dp)) {
            val isRec = recording != null
            Box(
                Modifier.size(80.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
                    .clickable(enabled = !finishing && videoCapture != null) {
                        when {
                            isRec -> recording?.stop()
                            countdown > 0 -> countdown = 0
                            timerSec > 0 -> countdown = timerSec
                            else -> startRecording()
                        }
                    }.testTag("camera_record"),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(if (isRec) 32.dp else 62.dp).clip(if (isRec) RoundedCornerShape(8.dp) else CircleShape).background(EditsColors.Red))
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit, tag: String, enabled: Boolean = true, badge: String? = null) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color(0x66000000)).clickable(enabled = enabled, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = if (enabled) Color.White else Color.Gray)
        if (badge != null) Text(badge, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
    }
}
