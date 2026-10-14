@file:OptIn(UnstableApi::class)

package com.myedits.app

import android.app.Application
import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.myedits.app.data.*
import com.myedits.app.engine.Exporter
import com.myedits.app.util.MediaImporter
import java.io.File

/** Tiny manual dependency container; keeps the app free of a DI framework. */
@UnstableApi
class AppContainer(val context: Context) {
    val repository = ProjectRepository(context.filesDir)
    val importer = MediaImporter(context, repository)
    val settings = SettingsStore(context)
    val ideas = IdeasStore(context)
    val activity = ActivityLog(context.getSharedPreferences("activity", Context.MODE_PRIVATE))
    val exporter = Exporter(context)
    val soundDir: File get() = File(context.filesDir, "sounds")
}

@UnstableApi
class EditsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}

val Context.container: AppContainer get() = (applicationContext as EditsApp).container
