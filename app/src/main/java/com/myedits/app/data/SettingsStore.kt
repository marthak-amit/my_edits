package com.myedits.app.data

import android.content.Context
import android.content.SharedPreferences

data class AppSettings(
    /** Short side in pixels: 720, 1080 or 2160. */
    val exportQuality: Int = 1080,
    /** 0 keeps the source frame rate. */
    val exportFps: Int = 0,
    val saveToGallery: Boolean = true,
    val haptics: Boolean = true,
    val snapToClips: Boolean = true,
)

class SettingsStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("settings", Context.MODE_PRIVATE))

    fun load(): AppSettings = AppSettings(
        exportQuality = prefs.getInt("quality", 1080),
        exportFps = prefs.getInt("fps", 0),
        saveToGallery = prefs.getBoolean("save_gallery", true),
        haptics = prefs.getBoolean("haptics", true),
        snapToClips = prefs.getBoolean("snap", true),
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putInt("quality", s.exportQuality)
            .putInt("fps", s.exportFps)
            .putBoolean("save_gallery", s.saveToGallery)
            .putBoolean("haptics", s.haptics)
            .putBoolean("snap", s.snapToClips)
            .apply()
    }
}

/** Simple local store for the Ideas tab. */
data class Idea(val id: String, val text: String, val createdAt: Long, val done: Boolean = false)

class IdeasStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("ideas", Context.MODE_PRIVATE))

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @kotlinx.serialization.Serializable
    private data class Row(val id: String, val text: String, val createdAt: Long, val done: Boolean = false)

    fun load(): List<Idea> = runCatching {
        val raw = prefs.getString("items", "[]") ?: "[]"
        json.decodeFromString<List<Row>>(raw).map { Idea(it.id, it.text, it.createdAt, it.done) }
    }.getOrDefault(emptyList())

    fun save(items: List<Idea>) {
        val raw = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Row.serializer()), items.map { Row(it.id, it.text, it.createdAt, it.done) })
        prefs.edit().putString("items", raw).apply()
    }
}
