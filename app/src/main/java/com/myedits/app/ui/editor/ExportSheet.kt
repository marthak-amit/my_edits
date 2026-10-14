package com.myedits.app.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.myedits.app.engine.CompositionBuilder
import com.myedits.app.ui.common.*
import com.myedits.app.ui.theme.EditsColors
import com.myedits.app.util.Device
import com.myedits.app.util.Share
import java.util.Locale
import kotlin.math.roundToInt

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(vm: EditorViewModel, onClose: () -> Unit, onSettingsChanged: (com.myedits.app.data.AppSettings) -> Unit, initial: com.myedits.app.data.AppSettings) {
    val state by vm.export.collectAsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ctx = LocalContext.current
    var quality by remember { mutableIntStateOf(initial.exportQuality) }
    var fps by remember { mutableIntStateOf(initial.exportFps) }
    var save by remember { mutableStateOf(initial.saveToGallery) }
    val p = vm.project
    val supported = remember(p.aspect) { listOf(720, 1080, 2160).associateWith { Device.supportsQuality(p.aspect, it) } }
    LaunchedEffect(supported) { if (supported[quality] == false) quality = supported.entries.lastOrNull { it.value }?.key ?: 720 }

    ModalBottomSheet(
        onDismissRequest = { if (state !is ExportUi.Running) { vm.dismissExport(); onClose() } },
        sheetState = sheet, containerColor = EditsColors.Surface, contentColor = Color.White,
        modifier = Modifier.testTag("export_sheet"),
    ) {
        Column(Modifier.fillMaxWidth().tagsAsResourceIds().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (val s = state) {
                ExportUi.Idle -> {
                    Text("Export", style = MaterialTheme.typography.titleLarge)
                    Text("Resolution", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(720 to "720p", 1080 to "1080p HD", 2160 to "4K").forEach { (q, label) ->
                            val ok = supported[q] == true
                            Chip(label + if (!ok) " ✕" else "", quality == q && ok, { if (ok) quality = q }, tag = "export_q_$q")
                        }
                    }
                    Text("Frame rate", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Original", 30 to "30 fps", 24 to "24 fps").forEach { (f, label) -> Chip(label, fps == f, { fps = f }, tag = "export_fps_$f") }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Save to gallery", Modifier.weight(1f))
                        Switch(save, { save = it }, Modifier.testTag("export_save_switch"))
                    }
                    val (w, h) = CompositionBuilder.canvasSize(p.aspect, quality)
                    val mb = (vm.totalMs / 1000.0) * (com.myedits.app.engine.Exporter(ctx).bitrateFor(quality, fps) + 128_000) / 8 / 1_048_576
                    Text("$w × $h · ${formatDuration(vm.totalMs)} · about ${String.format(Locale.US, "%.0f", mb.coerceAtLeast(0.1))} MB", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("export_summary"))
                    PrimaryButton("Export video", {
                        onSettingsChanged(initial.copy(exportQuality = quality, exportFps = fps, saveToGallery = save))
                        vm.startExport(quality, fps, save)
                    }, Modifier.fillMaxWidth(), enabled = !p.isEmpty, tag = "export_start")
                }
                is ExportUi.Running -> {
                    Text("Exporting…", style = MaterialTheme.typography.titleLarge)
                    LinearProgressIndicator(progress = { s.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).testTag("export_progress"), color = EditsColors.Blue, trackColor = EditsColors.Surface3)
                    Text("${(s.progress * 100).roundToInt()}% — keep the app open until it finishes", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall)
                    GhostButton("Cancel", { vm.cancelExport() }, Modifier.fillMaxWidth(), tag = "export_cancel")
                }
                is ExportUi.Done -> {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.CheckCircle, null, tint = EditsColors.Green, modifier = Modifier.size(48.dp))
                        Text("Your video is ready", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("export_done"))
                        Text(
                            if (s.galleryUri != null) "Saved to Movies/My Edits (${formatBytes(s.file.length())})" else "Rendered (${formatBytes(s.file.length())})",
                            color = EditsColors.TextDim, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        )
                    }
                    PrimaryButton("Share", { Share.shareVideo(ctx, Share.uriFor(ctx, s.file)) }, Modifier.fillMaxWidth(), tag = "export_share")
                    if (Share.isInstalled(ctx, Share.INSTAGRAM)) GhostButton("Share to Instagram", { Share.shareVideo(ctx, Share.uriFor(ctx, s.file), Share.INSTAGRAM) }, Modifier.fillMaxWidth(), tag = "export_instagram")
                    GhostButton("Done", { vm.dismissExport(); onClose() }, Modifier.fillMaxWidth(), tag = "export_finish")
                }
                is ExportUi.Failed -> {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.ErrorOutline, null, tint = EditsColors.Red, modifier = Modifier.size(48.dp))
                        Text("Export failed", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("export_failed"))
                        Text(s.message, color = EditsColors.TextDim, textAlign = TextAlign.Center)
                    }
                    PrimaryButton("Try again", { vm.dismissExport() }, Modifier.fillMaxWidth(), tag = "export_retry")
                    GhostButton("Close", { vm.dismissExport(); onClose() }, Modifier.fillMaxWidth(), tag = "export_close")
                }
            }
        }
    }
}

internal fun formatBytes(b: Long): String = when {
    b >= 1_048_576 -> String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
    b >= 1024 -> String.format(Locale.US, "%d KB", b / 1024)
    else -> "$b B"
}
