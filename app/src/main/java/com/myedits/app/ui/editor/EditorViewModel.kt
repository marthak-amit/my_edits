package com.myedits.app.ui.editor

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.myedits.app.AppContainer
import com.myedits.app.audio.SoundLibrary
import com.myedits.app.audio.TtsHelper
import com.myedits.app.audio.VoiceRecorder
import com.myedits.app.data.*
import com.myedits.app.engine.*
import com.myedits.app.data.Templates
import com.myedits.app.util.Thumbs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed interface Selection {
    data object None : Selection
    data class OfClip(val id: String) : Selection
    data class OfText(val id: String) : Selection
    data class OfSticker(val id: String) : Selection
    data class OfAudio(val id: String) : Selection
}

enum class Panel { NONE, SPEED, VOLUME, FADE, FILTER, ADJUST, CANVAS, AUDIO, SOUNDS, TEXT_STYLE, TEXT_EDIT, STICKERS, EXPORT, TTS }

sealed interface ExportUi {
    data object Idle : ExportUi
    data class Running(val progress: Float) : ExportUi
    data class Done(val file: File, val galleryUri: Uri?) : ExportUi
    data class Failed(val message: String) : ExportUi
}

@UnstableApi
class EditorViewModel(private val app: AppContainer, private val projectId: String) : ViewModel() {

    private val repo = app.repository
    val preview = PreviewPlayer(app.context)

    var project by mutableStateOf(
        repo.load(projectId) ?: Project(projectId, "Untitled", System.currentTimeMillis(), System.currentTimeMillis()),
    )
        private set
    var playheadMs by mutableStateOf(0L); private set
    var selection by mutableStateOf<Selection>(Selection.None); private set
    var panel by mutableStateOf(Panel.NONE); private set
    var busy by mutableStateOf<String?>(null); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var recording by mutableStateOf(false); private set
    var recordLevel by mutableStateOf(0f); private set
    var pxPerMs by mutableStateOf(0.09f)

    val playing: StateFlow<Boolean> get() = preview.playing

    private val _export = MutableStateFlow<ExportUi>(ExportUi.Idle)
    val export: StateFlow<ExportUi> = _export.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private var lastCoalesceKey: String? = null
    private var lastCoalesceAt = 0L
    private var saveJob: Job? = null
    private var exportJob: Job? = null
    private val voice = VoiceRecorder(app.context)
    private var recordStartMs = 0L
    private var recordFile: File? = null

    val settings: AppSettings get() = app.settings.load()
    val totalMs: Long get() = project.durationMs
    val selectedClip: Clip? get() = (selection as? Selection.OfClip)?.let { s -> project.clips.firstOrNull { it.id == s.id } }
    val selectedText: TextLayer? get() = (selection as? Selection.OfText)?.let { s -> project.texts.firstOrNull { it.id == s.id } }
    val selectedSticker: StickerLayer? get() = (selection as? Selection.OfSticker)?.let { s -> project.stickers.firstOrNull { it.id == s.id } }
    val selectedAudio: AudioTrack? get() = (selection as? Selection.OfAudio)?.let { s -> project.audios.firstOrNull { it.id == s.id } }

    init {
        preview.update(project, 0)
        viewModelScope.launch {
            // Mirror the player clock into the UI while playing.
            while (isActive) {
                if (preview.isPlaying) playheadMs = preview.positionMs.coerceIn(0L, totalMs)
                if (recording) recordLevel = voice.level()
                delay(16)
            }
        }
        viewModelScope.launch { preview.ended.collect { if (it) playheadMs = totalMs } }
        viewModelScope.launch { preview.error.collect { e -> if (e != null) _messages.tryEmit(e) } }
    }

    // ---- core state mutation --------------------------------------------------------------

    /**
     * Applies [f] to the project as one undoable step. Repeated edits with the same
     * [coalesce] key inside a short window (slider drags, gestures) merge into a single step.
     */
    fun edit(coalesce: String? = null, f: (Project) -> Project) {
        val before = project
        val after = f(before)
        if (after == before) return
        val now = System.currentTimeMillis()
        val merge = coalesce != null && coalesce == lastCoalesceKey && now - lastCoalesceAt < 900
        if (!merge) {
            undoStack.addLast(before)
            while (undoStack.size > 80) undoStack.removeFirst()
            redoStack.clear()
        }
        lastCoalesceKey = coalesce; lastCoalesceAt = now
        applyProject(after)
    }

    private fun applyProject(p: Project) {
        project = p.copy(updatedAt = System.currentTimeMillis())
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
        playheadMs = playheadMs.coerceIn(0L, totalMs)
        // drop selection / panels that point at things that no longer exist
        val ok = when (val s = selection) {
            Selection.None -> true
            is Selection.OfClip -> p.clips.any { it.id == s.id }
            is Selection.OfText -> p.texts.any { it.id == s.id }
            is Selection.OfSticker -> p.stickers.any { it.id == s.id }
            is Selection.OfAudio -> p.audios.any { it.id == s.id }
        }
        if (!ok) { selection = Selection.None; if (panel != Panel.EXPORT) panel = Panel.NONE }
        preview.update(project, playheadMs)
        scheduleSave()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(project); lastCoalesceKey = null
        applyProject(prev)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(project); lastCoalesceKey = null
        applyProject(next)
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(500); saveNow() }
    }

    private suspend fun saveNow() = withContext(Dispatchers.IO) {
        val p = project
        runCatching { repo.save(p) }
        p.clips.firstOrNull()?.let { c ->
            runCatching {
                val frame = Thumbs.frame(c.path, c.isImage, c.inMs, 360)
                frame?.let { bmp -> repo.thumbFile(p.id).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) } }
            }
        }
    }

    fun flushSave() {
        saveJob?.cancel()
        runBlocking(Dispatchers.IO) { runCatching { repo.save(project) } }
    }

    fun rename(name: String) = edit { it.copy(name = name.trim().ifEmpty { it.name }) }

    // ---- navigation within the editor -------------------------------------------------------

    fun openPanel(p: Panel) { panel = p }
    fun closePanel() { panel = Panel.NONE }

    fun select(s: Selection) {
        selection = s
        if (s == Selection.None) panel = Panel.NONE
        else if (panel == Panel.SPEED || panel == Panel.VOLUME || panel == Panel.FADE) panel = Panel.NONE
        // jump the playhead into the selected thing so what you edit is what you see
        when (s) {
            is Selection.OfText -> project.texts.firstOrNull { it.id == s.id }?.let { if (playheadMs !in it.startMs until it.endMs) seek(it.startMs) }
            is Selection.OfSticker -> project.stickers.firstOrNull { it.id == s.id }?.let { if (playheadMs !in it.startMs until it.endMs) seek(it.startMs) }
            else -> {}
        }
    }

    fun selectClipAtPlayhead() {
        ProjectOps.spanAt(project, playheadMs)?.let { selection = Selection.OfClip(it.clip.id) }
    }

    // ---- playback ------------------------------------------------------------------------

    fun togglePlay() {
        if (project.isEmpty) return
        if (preview.isPlaying) preview.pause() else {
            if (playheadMs >= totalMs - 30) { playheadMs = 0; preview.seekTo(0) }
            preview.seekTo(playheadMs)
            preview.play()
        }
    }

    fun pause() = preview.pause()

    fun seek(ms: Long) {
        val t = ms.coerceIn(0L, totalMs)
        playheadMs = t
        preview.seekTo(t)
    }

    fun scrubbing(on: Boolean) { if (on && preview.isPlaying) preview.pause(); preview.setScrubbing(on) }

    // ---- clip operations -------------------------------------------------------------------

    fun split() {
        pause()
        val r = when (val s = selection) {
            is Selection.OfText -> ProjectOps.splitText(project, s.id, playheadMs)
            is Selection.OfAudio -> ProjectOps.splitAudio(project, s.id, playheadMs)
            else -> ProjectOps.splitAt(project, playheadMs)
        }
        if (r == null) { _messages.tryEmit("Move the playhead inside the item to split it"); return }
        edit { r }
    }

    fun deleteSelected() {
        val s = selection
        edit {
            when (s) {
                is Selection.OfClip -> ProjectOps.deleteClip(it, s.id)
                is Selection.OfText -> ProjectOps.deleteText(it, s.id)
                is Selection.OfSticker -> ProjectOps.deleteSticker(it, s.id)
                is Selection.OfAudio -> ProjectOps.deleteAudio(it, s.id)
                Selection.None -> it
            }
        }
        selection = Selection.None; panel = Panel.NONE
    }

    fun duplicateSelected() {
        val s = selection
        edit {
            when (s) {
                is Selection.OfClip -> ProjectOps.duplicateClip(it, s.id)
                is Selection.OfText -> ProjectOps.duplicateText(it, s.id)
                is Selection.OfSticker -> ProjectOps.duplicateSticker(it, s.id)
                is Selection.OfAudio -> it.audios.firstOrNull { a -> a.id == s.id }?.let { a ->
                    ProjectOps.addAudio(it, a.copy(id = ProjectOps.newId(), startMs = (a.endMs).coerceAtMost((it.durationMs - 200).coerceAtLeast(0))))
                } ?: it
                Selection.None -> it
            }
        }
    }

    fun updateClip(coalesce: String? = null, f: (Clip) -> Clip) {
        val id = (selection as? Selection.OfClip)?.id ?: return
        edit(coalesce) { ProjectOps.updateClip(it, id, f) }
    }

    fun setSpeed(speed: Float) {
        val c = selectedClip ?: return
        edit("speed:${c.id}") { ProjectOps.setSpeed(it, c.id, speed) }
    }

    fun setVolume(v: Float) {
        val c = selectedClip ?: return
        edit("vol:${c.id}") { ProjectOps.setVolume(it, c.id, v) }
    }

    fun rotate() { val c = selectedClip ?: return; edit { ProjectOps.rotateClip(it, c.id) } }
    fun flipH() = updateClip { it.copy(flipH = !it.flipH) }
    fun flipV() = updateClip { it.copy(flipV = !it.flipV) }
    fun toggleFit() = updateClip { it.copy(fit = if (it.fit == FitMode.FIT) FitMode.FILL else FitMode.FIT) }
    fun setZoom(z: Float) = updateClip("zoom") { it.copy(zoom = z.coerceIn(1f, 3f)) }
    fun setFades(inMs: Long, outMs: Long) {
        val c = selectedClip ?: return
        edit("fade:${c.id}") { ProjectOps.setFades(it, c.id, inMs, outMs) }
    }

    fun trimSelectedClip(inMs: Long, outMs: Long) {
        val c = selectedClip ?: return
        edit("trim:${c.id}") { ProjectOps.trimClip(it, c.id, inMs, outMs) }
    }

    fun moveClip(id: String, toIndex: Int) = edit { ProjectOps.moveClip(it, id, toIndex) }

    fun setFilter(id: String, all: Boolean) {
        val f: (Clip) -> Clip = { it.copy(filter = id, filterIntensity = if (id == "none") 1f else it.filterIntensity) }
        if (all) edit { ProjectOps.updateAllClips(it, f) } else updateClip(null, f)
    }

    fun setFilterIntensity(v: Float, all: Boolean) {
        val f: (Clip) -> Clip = { it.copy(filterIntensity = v) }
        if (all) edit("fi-all") { ProjectOps.updateAllClips(it, f) } else updateClip("fi", f)
    }

    fun setAdjust(a: Adjust, all: Boolean) {
        val f: (Clip) -> Clip = { it.copy(adjust = a) }
        if (all) edit("adj-all") { ProjectOps.updateAllClips(it, f) } else updateClip("adj", f)
    }

    fun setCanvas(aspect: AspectRatio) = edit { ProjectOps.setCanvas(it, aspect) }

    // ---- media import ------------------------------------------------------------------------

    fun importMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy = "Importing ${uris.size} item${if (uris.size > 1) "s" else ""}…"
            try {
                val clips = app.importer.importVisual(projectId, uris)
                val after = (selection as? Selection.OfClip)?.id
                edit { Templates.applyOnFirstImport(ProjectOps.insertClipsAfter(it, after, clips), it.isEmpty) }
                _messages.tryEmit("Added ${clips.size} clip${if (clips.size > 1) "s" else ""}")
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Import failed")
            } finally { busy = null }
        }
    }

    fun addRecordedClip(file: File) {
        viewModelScope.launch {
            busy = "Adding recording…"
            try {
                val target = repo.newMediaFile(projectId, "mp4")
                withContext(Dispatchers.IO) { file.copyTo(target, overwrite = true) }
                val clip = withContext(Dispatchers.IO) { app.importer.clipFromFile(target, isImage = false) }
                edit { Templates.applyOnFirstImport(ProjectOps.appendClips(it, listOf(clip)), it.isEmpty) }
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Could not add the recording")
            } finally { busy = null }
        }
    }

    // ---- overlays: text & stickers -------------------------------------------------------------

    fun addText(text: String = "Your text") {
        if (project.isEmpty) { _messages.tryEmit("Add a clip first"); return }
        val t = ProjectOps.defaultText(project, playheadMs, text)
        edit { ProjectOps.addText(it, t) }
        selection = Selection.OfText(t.id); panel = Panel.TEXT_EDIT
    }

    fun updateText(coalesce: String? = null, f: (TextLayer) -> TextLayer) {
        val id = (selection as? Selection.OfText)?.id ?: return
        edit(coalesce) { ProjectOps.updateText(it, id, f) }
    }

    fun transformText(id: String, x: Float, y: Float, scale: Float, rotation: Float) =
        edit("tx:$id") { ProjectOps.updateText(it, id) { t -> t.copy(x = x, y = y, scale = scale, rotation = rotation) } }

    fun addSticker(emoji: String) {
        if (project.isEmpty) { _messages.tryEmit("Add a clip first"); return }
        val s = ProjectOps.defaultSticker(project, playheadMs, emoji)
        edit { ProjectOps.addSticker(it, s) }
        selection = Selection.OfSticker(s.id)
    }

    fun addImageOverlay(uris: List<Uri>) {
        if (project.isEmpty) { _messages.tryEmit("Add a clip first"); return }
        val uri = uris.firstOrNull() ?: return
        viewModelScope.launch {
            busy = "Adding photo…"
            try {
                val clip = app.importer.importVisual(projectId, listOf(uri)).first()
                if (!clip.isImage) throw com.myedits.app.util.MediaImporter.ImportException("Choose a photo for an overlay")
                val base = ProjectOps.defaultSticker(project, playheadMs, "")
                val s = base.copy(kind = StickerKind.IMAGE, path = clip.path, scale = 0.45f)
                edit { ProjectOps.addSticker(it, s) }
                selection = Selection.OfSticker(s.id)
            } catch (e: Exception) { _messages.tryEmit(e.message ?: "Could not add the photo") }
            finally { busy = null }
        }
    }

    fun transformSticker(id: String, x: Float, y: Float, scale: Float, rotation: Float) =
        edit("sx:$id") { ProjectOps.updateSticker(it, id) { s -> s.copy(x = x, y = y, scale = scale, rotation = rotation) } }

    /** Moves/resizes a layer on the timeline. */
    fun setLayerTiming(sel: Selection, startMs: Long, durationMs: Long) {
        when (sel) {
            is Selection.OfText -> edit("lt:${sel.id}") { ProjectOps.updateText(it, sel.id) { t -> t.copy(startMs = startMs, durationMs = durationMs) } }
            is Selection.OfSticker -> edit("lt:${sel.id}") { ProjectOps.updateSticker(it, sel.id) { s -> s.copy(startMs = startMs, durationMs = durationMs) } }
            else -> {}
        }
    }

    // ---- audio -------------------------------------------------------------------------------

    fun addMusic(uri: Uri) {
        viewModelScope.launch {
            busy = "Importing audio…"
            try {
                val info = app.importer.importAudio(projectId, uri)
                val t = ProjectOps.makeAudio(project, info.file.path, info.label, AudioKind.MUSIC, info.durationMs, playheadMs)
                edit { ProjectOps.addAudio(it, t) }
                selection = Selection.OfAudio(t.id)
            } catch (e: Exception) { _messages.tryEmit(e.message ?: "Could not add the audio") }
            finally { busy = null }
        }
    }

    fun addSound(id: String) {
        val item = SoundLibrary.byId(id) ?: return
        viewModelScope.launch {
            busy = "Preparing ${item.label}…"
            try {
                val file = withContext(Dispatchers.IO) { SoundLibrary.file(app.soundDir, id) }
                // copy into the project so duplicates/exports never depend on the shared cache
                val target = repo.newMediaFile(projectId, "wav")
                withContext(Dispatchers.IO) { file.copyTo(target, overwrite = true) }
                val kind = if (item.category == com.myedits.app.audio.SoundCategory.MUSIC) AudioKind.MUSIC else AudioKind.SFX
                val t = ProjectOps.makeAudio(project, target.path, item.label, kind, item.durationMs, playheadMs)
                edit { ProjectOps.addAudio(it, t) }
                selection = Selection.OfAudio(t.id)
            } catch (e: Exception) { _messages.tryEmit("Could not add the sound") }
            finally { busy = null }
        }
    }

    fun extractAudio() {
        val c = selectedClip ?: return
        viewModelScope.launch {
            busy = "Extracting audio…"
            try {
                val info = app.importer.extractAudio(projectId, c)
                val start = ProjectOps.spanOf(project, c.id)?.startMs ?: playheadMs
                val t = ProjectOps.makeAudio(project, info.file.path, info.label, AudioKind.EXTRACTED, info.durationMs, start)
                edit { ProjectOps.setVolume(ProjectOps.addAudio(it, t), c.id, 0f) }
                selection = Selection.OfAudio(t.id)
                _messages.tryEmit("Audio extracted – the clip's own sound was muted")
            } catch (e: Exception) { _messages.tryEmit(e.message ?: "Could not extract audio") }
            finally { busy = null }
        }
    }

    fun addSpeech(text: String, pitch: Float, rate: Float) {
        if (text.isBlank()) return
        viewModelScope.launch {
            busy = "Generating speech…"
            try {
                val target = repo.newMediaFile(projectId, "wav")
                val r = TtsHelper(app.context).synthesize(text, target, pitch, rate)
                r.onSuccess { f ->
                    val info = withContext(Dispatchers.IO) { app.importer.describeAudio(f, text.take(24)) }
                    val t = ProjectOps.makeAudio(project, f.path, text.take(24), AudioKind.TTS, info.durationMs, playheadMs)
                    edit { ProjectOps.addAudio(it, t) }
                    selection = Selection.OfAudio(t.id)
                    panel = Panel.NONE
                }.onFailure { target.delete(); _messages.tryEmit(it.message ?: "Speech failed") }
            } catch (e: Exception) { _messages.tryEmit(e.message ?: "Speech failed") }
            finally { busy = null }
        }
    }

    fun startRecording() {
        if (recording) return
        val f = repo.newMediaFile(projectId, "m4a")
        voice.start(f).onSuccess {
            recording = true; recordFile = f; recordStartMs = playheadMs
            if (!project.isEmpty && playheadMs < totalMs - 50) { preview.seekTo(playheadMs); preview.play() }
        }.onFailure { f.delete(); _messages.tryEmit("Could not start the microphone") }
    }

    fun stopRecording() {
        if (!recording) return
        recording = false; recordLevel = 0f
        preview.pause()
        val f = voice.stop()
        if (f == null) { _messages.tryEmit("Recording was too short"); return }
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { app.importer.describeAudio(f, "Voiceover ${project.audios.count { it.kind == AudioKind.VOICE } + 1}") }
                val t = ProjectOps.makeAudio(project, f.path, info.label, AudioKind.VOICE, info.durationMs, recordStartMs)
                edit { ProjectOps.addAudio(it, t) }
                app.activity.record(ActivityLog.RECORDED, info.durationMs)
                selection = Selection.OfAudio(t.id)
            } catch (e: Exception) { _messages.tryEmit(e.message ?: "Recording failed") }
        }
    }

    fun updateAudio(coalesce: String? = null, f: (AudioTrack) -> AudioTrack) {
        val id = (selection as? Selection.OfAudio)?.id ?: return
        edit(coalesce) { ProjectOps.updateAudio(it, id, f) }
    }

    fun moveAudio(id: String, startMs: Long) =
        edit("am:$id") { ProjectOps.updateAudio(it, id) { a -> a.copy(startMs = startMs.coerceIn(0L, (it.durationMs - 100).coerceAtLeast(0L))) } }

    fun trimAudio(id: String, inMs: Long, outMs: Long, startMs: Long) =
        edit("at:$id") { p -> ProjectOps.updateAudio(ProjectOps.trimAudio(p, id, inMs, outMs), id) { a -> a.copy(startMs = startMs.coerceAtLeast(0L)) } }

    // ---- export ------------------------------------------------------------------------------

    fun startExport(shortSide: Int, fps: Int, saveToGallery: Boolean) {
        if (_export.value is ExportUi.Running) return
        pause()
        _export.value = ExportUi.Running(0f)
        exportJob = viewModelScope.launch {
            try {
                val snapshot = project
                val file = app.exporter.export(snapshot, shortSide, fps) { _export.value = ExportUi.Running(it) }
                val uri = if (saveToGallery) runCatching { GallerySaver.save(app.context, file, snapshot.name) }.getOrNull() else null
                if (saveToGallery && uri == null) _messages.tryEmit("Exported, but saving to the gallery failed")
                edit { it.copy(exportCount = it.exportCount + 1) }
                app.activity.record(ActivityLog.EXPORTED, snapshot.durationMs)
                _export.value = ExportUi.Done(file, uri)
            } catch (e: CancellationException) {
                _export.value = ExportUi.Idle
                throw e
            } catch (e: Throwable) {
                _export.value = ExportUi.Failed(e.message ?: "Export failed")
            }
        }
    }

    fun cancelExport() { exportJob?.cancel(); _export.value = ExportUi.Idle }
    fun dismissExport() { if (_export.value !is ExportUi.Running) _export.value = ExportUi.Idle }

    override fun onCleared() {
        runCatching { voice.cancel() }
        exportJob?.cancel()
        flushSave()
        preview.release()
    }

    class Factory(private val app: AppContainer, private val projectId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(app, projectId) as T
    }
}
