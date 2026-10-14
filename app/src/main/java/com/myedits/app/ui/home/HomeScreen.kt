package com.myedits.app.ui.home

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.myedits.app.container
import com.myedits.app.data.*
import com.myedits.app.ui.common.*
import com.myedits.app.ui.editor.fieldColors
import com.myedits.app.ui.theme.EditsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

enum class HomeTab(val label: String) { PROJECTS("Projects"), TEMPLATES("Templates"), IDEAS("Ideas"), INSIGHTS("Insights") }

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenProject: (id: String, camera: Boolean) -> Unit, onSettings: () -> Unit) {
    val app = LocalContext.current.container
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(app))
    var tab by rememberSaveable { mutableStateOf(HomeTab.PROJECTS) }
    var showCreate by remember { mutableStateOf(false) }
    var chosenTemplate by remember { mutableStateOf<Template?>(null) }
    var pendingTemplate by remember { mutableStateOf<Template?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.open.collect { (id, cam) -> onOpenProject(id, cam) } }
    // Coming back from the editor: refresh thumbnails, names and stats.
    LaunchedEffect(Unit) { vm.refresh() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris -> vm.createFromUris(uris) }
    val pickForTemplate = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia((pendingTemplate?.slotCount ?: 2).coerceIn(2, 30))) { uris ->
        pendingTemplate?.let { vm.createFromTemplate(uris, it) }; pendingTemplate = null
    }
    fun startFromGallery() = pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    fun startTemplate(t: Template) {
        pendingTemplate = t
        pickForTemplate.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("home_screen")) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(EditsColors.Brand), contentAlignment = Alignment.Center) { Icon(Icons.Filled.ContentCut, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                    Text("Edits", style = MaterialTheme.typography.headlineLarge.copy(brush = EditsColors.Brand), modifier = Modifier.testTag("home_title"))
                }
                IconButton(onClick = onSettings, modifier = Modifier.testTag("home_settings")) { Icon(Icons.Filled.Settings, "Settings", tint = Color.White) }
            }
            TabRow(tab)  { tab = it }
            when (tab) {
                HomeTab.PROJECTS -> ProjectsTab(vm, onOpenProject, onPickTemplate = { chosenTemplate = it }, onSeeAllTemplates = { tab = HomeTab.TEMPLATES })
                HomeTab.IDEAS -> IdeasTab(vm, onStart = { text -> vm.createBlank(name = text.take(40)) })
                HomeTab.TEMPLATES -> TemplatesTab(onPick = { chosenTemplate = it })
                HomeTab.INSIGHTS -> InsightsTab(vm)
            }
        }

        if (tab == HomeTab.PROJECTS || tab == HomeTab.TEMPLATES) {
            Box(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp)
                    .clip(CircleShape).background(EditsColors.Brand).clickable { showCreate = true }
                    .padding(horizontal = 28.dp, vertical = 14.dp).testTag("home_create"),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Add, null, tint = Color.White)
                    Text("Create new", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 90.dp))

        vm.busy?.let { msg ->
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(EditsColors.Surface2).padding(24.dp).testTag("home_busy"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = EditsColors.Blue); Text(msg, color = Color.White)
                }
            }
        }
    }

    TemplateSheetHost(chosenTemplate, onDismiss = { chosenTemplate = null }, onUse = { t -> chosenTemplate = null; startTemplate(t) })
    if (showCreate) {
        ModalBottomSheet(onDismissRequest = { showCreate = false }, containerColor = EditsColors.Surface, contentColor = Color.White, modifier = Modifier.testTag("create_sheet")) {
            Column(Modifier.fillMaxWidth().tagsAsResourceIds().padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Create new", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                CreateRow(Icons.Filled.PhotoLibrary, "From gallery", "Pick videos and photos", "create_gallery") { showCreate = false; startFromGallery() }
                CreateRow(Icons.Filled.Videocam, "Record", "Shoot a clip with the camera", "create_camera") { showCreate = false; vm.createBlank(camera = true) }
                CreateRow(Icons.Filled.NoteAdd, "Blank project", "Add media later", "create_blank") { showCreate = false; vm.createBlank() }
            }
        }
    }
}

@Composable
private fun TemplateSheetHost(chosen: Template?, onDismiss: () -> Unit, onUse: (Template) -> Unit) {
    chosen?.let { t -> TemplateSheet(t, onDismiss) { onUse(t) } }
}

@Composable
private fun CreateRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(EditsColors.Surface2).clickable(onClick = onClick).padding(14.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(EditsColors.Brand), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White) }
        Column { Text(title, fontWeight = FontWeight.SemiBold, color = Color.White); Text(sub, color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun TabRow(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        HomeTab.entries.forEach { t ->
            val sel = t == selected
            Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect(t) }.padding(horizontal = 10.dp, vertical = 8.dp).testTag("tab_${t.name}"), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(t.label, color = if (sel) Color.White else EditsColors.TextDim, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.height(3.dp).width(if (sel) 28.dp else 0.dp).clip(CircleShape).background(EditsColors.Brand))
            }
        }
    }
    HorizontalDivider(color = EditsColors.Outline)
}

// ---- projects -----------------------------------------------------------------------------------

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectsTab(vm: HomeViewModel, onOpen: (String, Boolean) -> Unit, onPickTemplate: (Template) -> Unit, onSeeAllTemplates: () -> Unit) {
    var menuFor by remember { mutableStateOf<Project?>(null) }
    var renameFor by remember { mutableStateOf<Project?>(null) }
    var deleteFor by remember { mutableStateOf<Project?>(null) }
    var sortMenu by remember { mutableStateOf(false) }

    if (vm.loading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = EditsColors.Blue) }; return }
    if (vm.projects.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(12.dp))
            TemplateRail(onPickTemplate, onSeeAllTemplates)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("🎬", "No projects yet", "Create your first video. Everything you edit is saved automatically as a draft.", Modifier.testTag("projects_empty"))
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize().testTag("projects_grid"),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 110.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "template_rail", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) { TemplateRail(onPickTemplate, onSeeAllTemplates, edge = 4.dp) }
            item(key = "sort_row", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${vm.projects.size} project${if (vm.projects.size == 1) "" else "s"}", color = EditsColors.TextDim, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Box {
                    TextButton(onClick = { sortMenu = true }, modifier = Modifier.testTag("sort_button")) {
                        Icon(Icons.Filled.SwapVert, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(vm.sort.label, color = Color.White)
                    }
                    DropdownMenu(sortMenu, { sortMenu = false }, containerColor = EditsColors.Surface2) {
                        SortMode.entries.forEach { m -> DropdownMenuItem(text = { Text(m.label, color = Color.White) }, onClick = { vm.sort = m; sortMenu = false }, modifier = Modifier.testTag("sort_${m.name}")) }
                    }
                }
            }
            }
            items(vm.sortedProjects, key = { it.id }) { p ->
                ProjectCard(p, vm, onClick = { onOpen(p.id, false) }, onMenu = { menuFor = p })
            }
        }
    }

    menuFor?.let { p ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }, containerColor = EditsColors.Surface, contentColor = Color.White, modifier = Modifier.testTag("project_menu")) {
            Column(Modifier.tagsAsResourceIds().padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                MenuRow(Icons.Filled.Edit, "Rename", "menu_rename") { menuFor = null; renameFor = p }
                MenuRow(Icons.Filled.ContentCopy, "Duplicate", "menu_duplicate") { menuFor = null; vm.duplicate(p.id) }
                MenuRow(Icons.Filled.Delete, "Delete", "menu_delete", tint = EditsColors.Red) { menuFor = null; deleteFor = p }
            }
        }
    }
    renameFor?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renameFor = null }, containerColor = EditsColors.Surface2,
            title = { Text("Rename project", color = Color.White) },
            text = { OutlinedTextField(text, { text = it.take(60) }, singleLine = true, colors = fieldColors(), modifier = Modifier.testTag("home_rename_input")) },
            confirmButton = { TextButton({ vm.rename(p.id, text); renameFor = null }, enabled = text.isNotBlank(), modifier = Modifier.testTag("home_rename_ok")) { Text("Save", color = EditsColors.Blue) } },
            dismissButton = { TextButton({ renameFor = null }) { Text("Cancel", color = EditsColors.TextDim) } },
        )
    }
    deleteFor?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteFor = null }, containerColor = EditsColors.Surface2,
            title = { Text("Delete “${p.name}”?", color = Color.White) },
            text = { Text("The draft and its imported media will be removed from this device. This can't be undone.", color = EditsColors.TextDim) },
            confirmButton = { TextButton({ vm.delete(p.id); deleteFor = null }, modifier = Modifier.testTag("home_delete_ok")) { Text("Delete", color = EditsColors.Red) } },
            dismissButton = { TextButton({ deleteFor = null }) { Text("Cancel", color = Color.White) } },
        )
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String, tint: Color = Color.White, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(14.dp).testTag(tag), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint); Text(label, color = tint)
    }
}

@UnstableApi
@Composable
private fun ProjectCard(p: Project, vm: HomeViewModel, onClick: () -> Unit, onMenu: () -> Unit) {
    val app = LocalContext.current.container
    val thumb by produceState<android.graphics.Bitmap?>(null, p.id, p.updatedAt) {
        value = withContext(Dispatchers.IO) { app.repository.thumbFile(p.id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }
    }
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).testTag("project_${p.id}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(12.dp)).background(EditsColors.Surface2)) {
            val b = thumb
            if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2A2A30), Color(0xFF16161A)))), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Movie, null, tint = EditsColors.TextDim, modifier = Modifier.size(36.dp)) }
            Box(Modifier.fillMaxWidth().height(56.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
            Text(formatDuration(p.durationMs), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
            Text("${p.aspect.label} · ${p.clips.size} clip${if (p.clips.size == 1) "" else "s"}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x66000000)).padding(horizontal = 6.dp, vertical = 2.dp))
            IconButton(onClick = onMenu, modifier = Modifier.align(Alignment.TopEnd).size(40.dp).testTag("menu_${p.id}")) {
                Icon(Icons.Filled.MoreVert, "More", tint = Color.White, modifier = Modifier.background(Color(0x66000000), CircleShape).padding(4.dp))
            }
        }
        Text(p.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        Text(relativeTime(p.updatedAt), color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 2.dp))
    }
}

internal fun relativeTime(t: Long, now: Long = System.currentTimeMillis()): String {
    val d = now - t
    return when {
        d < 60_000 -> "Just now"
        d < 3_600_000 -> "${d / 60_000} min ago"
        d < 86_400_000 -> "${d / 3_600_000} h ago"
        d < 7 * 86_400_000L -> "${d / 86_400_000} d ago"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(t))
    }
}

// ---- ideas --------------------------------------------------------------------------------------

@UnstableApi
@Composable
private fun IdeasTab(vm: HomeViewModel, onStart: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it.take(140) }, placeholder = { Text("Jot down a video idea…") }, singleLine = true, colors = fieldColors(), modifier = Modifier.weight(1f).testTag("idea_input"))
            IconButton(onClick = { vm.addIdea(text); text = "" }, enabled = text.isNotBlank(), modifier = Modifier.size(48.dp).background(if (text.isNotBlank()) EditsColors.Blue else EditsColors.Surface3, CircleShape).testTag("idea_add")) { Icon(Icons.Filled.Add, "Add idea", tint = Color.White) }
        }
        Spacer(Modifier.height(12.dp))
        if (vm.ideas.isEmpty()) {
            EmptyState("💡", "Capture ideas before they vanish", "Write down concepts, hooks and shot lists, then turn any of them into a project.", Modifier.testTag("ideas_empty"))
        } else LazyColumn(Modifier.fillMaxSize().testTag("ideas_list"), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(vm.ideas, key = { it.id }) { idea ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(EditsColors.Surface2).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(idea.done, { vm.toggleIdea(idea.id) }, colors = CheckboxDefaults.colors(checkedColor = EditsColors.Blue), modifier = Modifier.testTag("idea_check_${idea.id}"))
                    Text(idea.text, color = if (idea.done) EditsColors.TextDim else Color.White, modifier = Modifier.weight(1f).padding(vertical = 12.dp), textDecoration = if (idea.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                    TextButton(onClick = { onStart(idea.text) }, modifier = Modifier.testTag("idea_start_${idea.id}")) { Text("Create", color = EditsColors.Blue) }
                    IconButton(onClick = { vm.deleteIdea(idea.id) }, modifier = Modifier.testTag("idea_delete_${idea.id}")) { Icon(Icons.Filled.Close, "Delete idea", tint = EditsColors.TextDim) }
                }
            }
        }
    }
}

// ---- insights -----------------------------------------------------------------------------------

@Composable
private fun InsightsTab(vm: HomeViewModel) {
    val stats = remember(vm.projects, vm.events) { computeInsights(vm.projects, vm.events) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("insights"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your activity", style = MaterialTheme.typography.titleMedium, color = Color.White)
        Text("Based on what you do in this app on this device.", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Projects", stats.projects.toString(), Modifier.weight(1f), "stat_projects")
            StatCard("Exports", stats.exports.toString(), Modifier.weight(1f), "stat_exports")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Footage edited", formatDuration(stats.totalMs), Modifier.weight(1f), "stat_footage")
            StatCard("Day streak", stats.streak.toString(), Modifier.weight(1f), "stat_streak")
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(EditsColors.Surface2).padding(16.dp)) {
            Text("Last 7 days", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("Projects created and exports per day", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            BarChart(stats.perDay, Modifier.fillMaxWidth().height(120.dp).testTag("insights_chart"))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                stats.dayLabels.forEach { Text(it, color = EditsColors.TextDim, style = MaterialTheme.typography.labelSmall) }
            }
        }
        if (stats.projects == 0 && stats.exports == 0) Text("Nothing yet — create and export a video to see your numbers grow.", color = EditsColors.TextDim, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("insights_empty"))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier, tag: String) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(EditsColors.Surface2).padding(16.dp).semantics(mergeDescendants = true) {}.testTag(tag)) {
        Text(value, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(label, color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BarChart(values: List<Int>, modifier: Modifier) {
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    androidx.compose.foundation.Canvas(modifier) {
        val n = values.size
        val gap = 10.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        drawLine(Color(0xFF3A3A40), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 2f)
        values.forEachIndexed { i, v ->
            val h = if (v == 0) 4f else (size.height - 8f) * v / max
            drawRoundRect(
                if (v == 0) Color(0xFF3A3A40) else Color(0xFF0A84FF),
                topLeft = Offset(i * (bw + gap), size.height - h - 2f), size = Size(bw, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
            )
        }
    }
}

data class Insights(val projects: Int, val exports: Int, val totalMs: Long, val streak: Int, val perDay: List<Int>, val dayLabels: List<String>)

/** Pure aggregation, kept separate so it is unit-testable. */
fun computeInsights(projects: List<Project>, events: List<ActivityEvent>, now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): Insights {
    fun dayIndex(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(tz.toZoneId()).toLocalDate().toEpochDay()
    val today = dayIndex(now)
    val perDay = (6 downTo 0).map { back -> events.count { (it.type == ActivityLog.CREATED || it.type == ActivityLog.EXPORTED) && dayIndex(it.atMs) == today - back } }
    val labels = (6 downTo 0).map { back ->
        java.time.LocalDate.ofEpochDay(today - back).dayOfWeek.value.let { arrayOf("M", "T", "W", "T", "F", "S", "S")[it - 1] }
    }
    val activeDays = events.map { dayIndex(it.atMs) }.toSet()
    var streak = 0
    var d = if (today in activeDays) today else today - 1
    while (d in activeDays) { streak++; d-- }
    return Insights(
        projects = projects.size,
        exports = events.count { it.type == ActivityLog.EXPORTED },
        totalMs = projects.sumOf { it.durationMs },
        streak = streak, perDay = perDay, dayLabels = labels,
    )
}
