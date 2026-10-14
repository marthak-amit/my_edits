package com.myedits.app.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.myedits.app.container
import com.myedits.app.ui.camera.CameraScreen
import com.myedits.app.ui.common.*
import com.myedits.app.ui.theme.EditsColors

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    projectId: String,
    openCameraOnStart: Boolean,
    onExit: () -> Unit,
    previewFactory: (android.content.Context) -> com.myedits.app.engine.PreviewController = { com.myedits.app.engine.PreviewPlayer(it) },
    previewSurface: @Composable BoxScope.(EditorViewModel) -> Unit = { DefaultPreviewSurface(it) },
) {
    val ctx = LocalContext.current
    val app = ctx.container
    val vm: EditorViewModel = viewModel(key = "editor_$projectId", factory = EditorViewModel.Factory(app, projectId, previewFactory))
    val snackbar = remember { SnackbarHostState() }
    var showRename by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(openCameraOnStart) }
    var settings by remember { mutableStateOf(app.settings.load()) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) } }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris -> vm.importMedia(uris) }
    val pickOverlay = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.addImageOverlay(listOf(uri)) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.addMusic(uri) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startRecording() else vm.let { /* surfaced below */ }
    }
    val actions = remember {
        EditorActions(
            pickMedia = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            pickOverlay = { pickOverlay.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            pickAudio = { pickAudio.launch(arrayOf("audio/*")) },
            startVoice = {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startRecording()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            },
            openCamera = { showCamera = true },
        )
    }

    val exportState by vm.export.collectAsState()
    val exporting = exportState !is ExportUi.Idle

    fun leave() { vm.pause(); vm.flushSave(); onExit() }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) { if (vm.recording) vm.stopRecording(); vm.pause(); vm.flushSave() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler {
        when {
            showExport || exporting -> { if (exportState !is ExportUi.Running) { vm.dismissExport(); showExport = false } }
            vm.recording -> vm.stopRecording()
            vm.panel != Panel.NONE -> vm.closePanel()
            vm.selection != Selection.None -> vm.select(Selection.None)
            else -> leave()
        }
    }

    if (showCamera) {
        CameraScreen(onClose = { showCamera = false }, onRecorded = { f -> showCamera = false; vm.addRecordedClip(f) })
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding().testTag("editor_screen")) {
            // Keep the preview big: tool panels get a bounded share of the height and the timeline
            // shrinks to its essentials while a panel is open.
            val panelMax = (maxHeight * 0.34f).coerceIn(190.dp, 300.dp)
            val timelineMax = if (vm.panel != Panel.NONE) 124.dp else 216.dp
            Column(Modifier.fillMaxSize()) {
                TopBar(vm, onClose = ::leave, onRename = { showRename = true }, onExport = { vm.pause(); showExport = true })
                PreviewPane(vm, Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp), previewSurface)
                Transport(vm)
                Timeline(
                    project = vm.project, playheadMs = vm.playheadMs, selection = vm.selection, dpPerMs = vm.pxPerMs,
                    onZoom = { vm.pxPerMs = it }, onSeek = vm::seek, onScrubbing = vm::scrubbing, onSelect = vm::select,
                    onTrimClip = { id, i, o -> vm.select(Selection.OfClip(id)); vm.trimSelectedClip(i, o) },
                    onLayerTiming = vm::setLayerTiming,
                    onTrimAudio = vm::trimAudio,
                    onAddClip = actions.pickMedia,
                    maxHeight = timelineMax,
                )
                EditorBottom(vm, actions, Modifier.heightIn(max = panelMax))
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).systemBarsPadding().padding(top = 56.dp)) { Snackbar(it, containerColor = EditsColors.Surface3, contentColor = Color.White) }

        vm.busy?.let { msg ->
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(EditsColors.Surface2).padding(24.dp).testTag("busy_overlay"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = EditsColors.Blue)
                    Text(msg, color = Color.White)
                }
            }
        }
    }

    if (showRename) RenameDialog(vm.project.name, onDismiss = { showRename = false }, onConfirm = { vm.rename(it); showRename = false })

    if (showExport || exporting) {
        ExportSheet(vm, onClose = { showExport = false }, onSettingsChanged = { settings = it; app.settings.save(it) }, initial = settings)
    }
}

@UnstableApi
@Composable
private fun TopBar(vm: EditorViewModel, onClose: () -> Unit, onRename: () -> Unit, onExport: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose, modifier = Modifier.testTag("editor_close")) { Icon(Icons.Filled.Close, "Close editor", tint = Color.White) }
        Text(
            vm.project.name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onRename).padding(horizontal = 8.dp, vertical = 8.dp).testTag("editor_title"),
        )
        IconButton(onClick = vm::undo, enabled = vm.canUndo, modifier = Modifier.testTag("editor_undo")) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = if (vm.canUndo) Color.White else EditsColors.Surface3) }
        IconButton(onClick = vm::redo, enabled = vm.canRedo, modifier = Modifier.testTag("editor_redo")) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo", tint = if (vm.canRedo) Color.White else EditsColors.Surface3) }
        Spacer(Modifier.width(4.dp))
        PrimaryButton("Export", onExport, enabled = !vm.project.isEmpty, tag = "editor_export")
        Spacer(Modifier.width(8.dp))
    }
}

@UnstableApi
@Composable
private fun Transport(vm: EditorViewModel) {
    val playing by vm.playing.collectAsState()
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${formatTime(vm.playheadMs)} / ${formatTime(vm.totalMs)}", color = Color.White, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).testTag("time_label"),
        )
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Color.White).clickable(enabled = !vm.project.isEmpty) { vm.togglePlay() }.testTag("play_button"),
            contentAlignment = Alignment.Center,
        ) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", tint = Color.Black, modifier = Modifier.size(28.dp)) }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { vm.pxPerMs = (vm.pxPerMs / 1.5f).coerceAtLeast(0.006f) }, modifier = Modifier.testTag("zoom_out")) { Icon(Icons.Filled.ZoomOut, "Zoom out", tint = Color.White) }
            IconButton(onClick = { vm.pxPerMs = (vm.pxPerMs * 1.5f).coerceAtMost(0.45f) }, modifier = Modifier.testTag("zoom_in")) { Icon(Icons.Filled.ZoomIn, "Zoom in", tint = Color.White) }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = EditsColors.Surface2,
        title = { Text("Rename project", color = Color.White) },
        text = { OutlinedTextField(text, { text = it.take(60) }, singleLine = true, colors = fieldColors(), modifier = Modifier.testTag("rename_input")) },
        confirmButton = { TextButton({ onConfirm(text) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("rename_ok")) { Text("Save", color = EditsColors.Blue) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = EditsColors.TextDim) } },
    )
}
