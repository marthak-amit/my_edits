package com.myedits.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.myedits.app.BuildConfig
import com.myedits.app.container
import com.myedits.app.ui.common.Chip
import com.myedits.app.ui.theme.EditsColors
import java.io.File

@UnstableApi
@Composable
fun SettingsScreen(onBack: () -> Unit, onDeleteAll: () -> Unit) {
    val app = LocalContext.current.container
    var s by remember { mutableStateOf(app.settings.load()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var cacheMsg by remember { mutableStateOf<String?>(null) }
    fun update(n: com.myedits.app.data.AppSettings) { s = n; app.settings.save(n) }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().testTag("settings_screen")) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = Color.White)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Section("Export defaults")
            Text("Resolution", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(720 to "720p", 1080 to "1080p HD", 2160 to "4K").forEach { (q, l) -> Chip(l, s.exportQuality == q, { update(s.copy(exportQuality = q)) }, tag = "set_q_$q") }
            }
            Spacer(Modifier.height(6.dp))
            Text("Frame rate", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Original", 30 to "30 fps", 24 to "24 fps").forEach { (f, l) -> Chip(l, s.exportFps == f, { update(s.copy(exportFps = f)) }, tag = "set_fps_$f") }
            }
            SwitchRow("Save exports to gallery", "Copies finished videos to Movies/My Edits", s.saveToGallery, "set_gallery") { update(s.copy(saveToGallery = it)) }

            Section("Editor")
            SwitchRow("Haptic feedback", "Subtle vibration on tool taps", s.haptics, "set_haptics") { update(s.copy(haptics = it)) }

            Section("Storage")
            ActionRow("Clear temporary files", cacheMsg ?: "Removes cached exports and camera takes", "set_clear_cache") {
                val freed = listOf("exports", "camera").sumOf { n -> File(app.context.cacheDir, n).let { d -> val l = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }; d.deleteRecursively(); l } }
                cacheMsg = "Freed ${"%.1f".format(freed / 1_048_576.0)} MB"
            }
            ActionRow("Delete all projects", "Permanently removes every draft and its media", "set_delete_all", danger = true) { confirmDelete = true }

            Section("About")
            Text("My Edits ${BuildConfig.VERSION_NAME}", color = Color.White)
            Text(
                "A video editor in the spirit of Instagram’s Edits. Not affiliated with or endorsed by Instagram or Meta. " +
                    "Music and effects are synthesised on-device; auto-captions and cloud features are not included.",
                color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, containerColor = EditsColors.Surface2,
        title = { Text("Delete all projects?", color = Color.White) },
        text = { Text("Every draft and its imported media will be removed from this device.", color = EditsColors.TextDim) },
        confirmButton = { TextButton({ confirmDelete = false; onDeleteAll() }, modifier = Modifier.testTag("set_delete_confirm")) { Text("Delete everything", color = EditsColors.Red) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel", color = Color.White) } },
    )
}

@Composable private fun Section(t: String) { Text(t, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)) }

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = Color.White); Text(sub, color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall) }
        Switch(checked, onChange, Modifier.testTag(tag))
    }
}

@Composable
private fun ActionRow(title: String, sub: String, tag: String, danger: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp).testTag(tag)) {
        Text(title, color = if (danger) EditsColors.Red else Color.White); Text(sub, color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall)
    }
}
