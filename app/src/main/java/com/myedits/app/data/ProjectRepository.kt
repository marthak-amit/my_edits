package com.myedits.app.data

import kotlinx.serialization.json.Json
import java.io.File

/**
 * File based project store. Each project lives in `<root>/projects/<id>/project.json` with its
 * imported media next to it, so deleting or duplicating a project is a simple directory operation.
 */
class ProjectRepository(private val root: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val projectsDir: File get() = File(root, "projects").apply { mkdirs() }

    fun projectDir(id: String): File = File(projectsDir, id).apply { mkdirs() }
    fun mediaDir(id: String): File = File(projectDir(id), "media").apply { mkdirs() }
    fun thumbFile(id: String): File = File(projectDir(id), "thumb.jpg")
    private fun jsonFile(id: String) = File(projectDir(id), "project.json")

    fun list(): List<Project> =
        projectsDir.listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { load(it.name) }
            .sortedByDescending { it.updatedAt }

    fun load(id: String): Project? = runCatching {
        val f = File(File(projectsDir, id), "project.json")
        if (!f.exists()) null else json.decodeFromString(Project.serializer(), f.readText())
    }.getOrNull()

    /** Atomic write (temp file + rename) so a crash mid-save never corrupts a draft. */
    fun save(p: Project) {
        val target = jsonFile(p.id)
        val tmp = File(target.parentFile, "project.json.tmp")
        tmp.writeText(json.encodeToString(Project.serializer(), p))
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText()); tmp.delete()
        }
    }

    fun delete(id: String) {
        File(projectsDir, id).deleteRecursively()
    }

    fun rename(id: String, name: String) {
        val p = load(id) ?: return
        save(p.copy(name = name.trim().ifEmpty { p.name }, updatedAt = System.currentTimeMillis()))
    }

    /** Deep copy including media; paths inside the copy are rewritten to the new directory. */
    fun duplicate(id: String, newId: String, newName: String): Project? {
        val src = load(id) ?: return null
        val srcRoot = File(projectsDir, id)
        val dstRoot = File(projectsDir, newId)
        srcRoot.copyRecursively(dstRoot, overwrite = true)
        val oldPrefix = srcRoot.absolutePath
        val newPrefix = dstRoot.absolutePath
        fun fix(path: String) = if (path.startsWith(oldPrefix)) newPrefix + path.removePrefix(oldPrefix) else path
        val now = System.currentTimeMillis()
        val copy = src.copy(
            id = newId, name = newName, createdAt = now, updatedAt = now, exportCount = 0,
            clips = src.clips.map { it.copy(path = fix(it.path)) },
            stickers = src.stickers.map { it.copy(path = fix(it.path)) },
            audios = src.audios.map { it.copy(path = fix(it.path)) },
        )
        save(copy)
        return copy
    }

    /** Names media files uniquely inside the project's media folder. */
    fun newMediaFile(projectId: String, extension: String): File =
        File(mediaDir(projectId), "${System.currentTimeMillis()}_${(0..9999).random()}.$extension")
}
