package com.myedits.app.ui.home

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.myedits.app.AppContainer
import com.myedits.app.data.*
import com.myedits.app.engine.ProjectOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortMode(val label: String) { RECENT("Recently edited"), CREATED("Date created"), NAME("Name") }

@UnstableApi
class HomeViewModel(private val app: AppContainer) : ViewModel() {
    var projects by mutableStateOf<List<Project>>(emptyList()); private set
    var ideas by mutableStateOf<List<Idea>>(emptyList()); private set
    var events by mutableStateOf<List<ActivityEvent>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var busy by mutableStateOf<String?>(null); private set
    var sort by mutableStateOf(SortMode.RECENT)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    private val _open = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 1)
    /** Emits (projectId, openCamera) when a new project should be opened. */
    val open: SharedFlow<Pair<String, Boolean>> = _open.asSharedFlow()

    val sortedProjects: List<Project>
        get() = when (sort) {
            SortMode.RECENT -> projects.sortedByDescending { it.updatedAt }
            SortMode.CREATED -> projects.sortedByDescending { it.createdAt }
            SortMode.NAME -> projects.sortedBy { it.name.lowercase() }
        }

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val (p, i, e) = withContext(Dispatchers.IO) { Triple(app.repository.list(), app.ideas.load(), app.activity.load()) }
            projects = p; ideas = i; events = e; loading = false
        }
    }

    private fun nextName(): String {
        val used = projects.map { it.name }.toSet()
        var n = projects.size + 1
        while ("Project $n" in used) n++
        return "Project $n"
    }

    private suspend fun newProject(name: String? = null, aspect: AspectRatio = AspectRatio.R9_16): Project {
        val now = System.currentTimeMillis()
        val p = Project(ProjectOps.newId(), name?.trim()?.ifEmpty { null } ?: nextName(), now, now, aspect = aspect)
        withContext(Dispatchers.IO) { app.repository.save(p); app.activity.record(ActivityLog.CREATED) }
        return p
    }

    fun createBlank(name: String? = null, camera: Boolean = false) {
        viewModelScope.launch { val p = newProject(name); refresh(); _open.emit(p.id to camera) }
    }

    /** Builds a project from an Edits-style [template]: the picked media fill its slots in order. */
    fun createFromTemplate(uris: List<Uri>, template: Template) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy = "Applying “${template.title}”…"
            var created: Project? = null
            try {
                val picked = uris.take(template.slotCount)
                val p = newProject(name = template.title, aspect = template.aspect).also { created = it }
                val clips = app.importer.importVisual(p.id, picked)
                val done = withContext(Dispatchers.IO) {
                    Templates.apply(p, template, clips) { id, startMs -> templateSound(p.id, id, startMs) }
                }
                withContext(Dispatchers.IO) { app.repository.save(done); app.repository.writeCover(done) }
                refresh()
                _open.emit(p.id to false)
            } catch (e: Exception) {
                created?.let { withContext(Dispatchers.IO) { app.repository.delete(it.id) } }
                refresh()
                _messages.tryEmit(e.message ?: "Could not apply the template")
            } finally { busy = null }
        }
    }

    /** A synthesised library sound copied into the project, so the project never depends on the shared cache. */
    private fun templateSound(projectId: String, soundId: String, startMs: Long): AudioTrack? = runCatching {
        val item = com.myedits.app.audio.SoundLibrary.byId(soundId) ?: return null
        val cached = com.myedits.app.audio.SoundLibrary.file(app.soundDir, soundId)
        val target = app.repository.newMediaFile(projectId, "wav")
        cached.copyTo(target, overwrite = true)
        val kind = if (item.category == com.myedits.app.audio.SoundCategory.MUSIC) AudioKind.MUSIC else AudioKind.SFX
        AudioTrack(id = ProjectOps.newId(), path = target.path, label = item.label, kind = kind, sourceDurationMs = item.durationMs, startMs = startMs)
    }.getOrNull()

    fun createFromUris(uris: List<Uri>, decorate: ((Project) -> Project)? = null) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy = "Importing ${uris.size} item${if (uris.size > 1) "s" else ""}…"
            var created: Project? = null
            try {
                val p = newProject().also { created = it }
                val clips = app.importer.importVisual(p.id, uris)
                var withClips = ProjectOps.appendClips(p, clips)
                decorate?.let { withClips = it(withClips) }
                withContext(Dispatchers.IO) { app.repository.save(withClips); app.repository.writeCover(withClips) }
                refresh()
                _open.emit(p.id to false)
            } catch (e: Exception) {
                created?.let { withContext(Dispatchers.IO) { app.repository.delete(it.id) } }
                refresh()
                _messages.tryEmit(e.message ?: "Could not create the project")
            } finally { busy = null }
        }
    }

    fun delete(id: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { app.repository.delete(id) }; refresh(); _messages.tryEmit("Project deleted")
    }

    fun rename(id: String, name: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { app.repository.rename(id, name) }; refresh()
    }

    fun duplicate(id: String) = viewModelScope.launch {
        busy = "Duplicating…"
        try {
            val src = projects.firstOrNull { it.id == id }
            withContext(Dispatchers.IO) { app.repository.duplicate(id, ProjectOps.newId(), (src?.name ?: "Project") + " copy") }
            refresh(); _messages.tryEmit("Project duplicated")
        } finally { busy = null }
    }

    fun deleteAll() = viewModelScope.launch {
        withContext(Dispatchers.IO) { projects.forEach { app.repository.delete(it.id) } }; refresh()
    }

    // ---- ideas ----
    fun addIdea(text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        ideas = listOf(Idea(ProjectOps.newId(), t, System.currentTimeMillis())) + ideas
        app.ideas.save(ideas)
    }
    fun toggleIdea(id: String) { ideas = ideas.map { if (it.id == id) it.copy(done = !it.done) else it }; app.ideas.save(ideas) }
    fun deleteIdea(id: String) { ideas = ideas.filterNot { it.id == id }; app.ideas.save(ideas) }

    class Factory(private val app: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(app) as T
    }
}
