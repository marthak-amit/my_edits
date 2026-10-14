package com.myedits.app.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import com.myedits.app.data.Project
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ExportFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Renders a project to an H.264/AAC MP4 with Media3 Transformer. */
@UnstableApi
class Exporter(private val context: Context) {

    fun bitrateFor(shortSide: Int, fps: Int): Int {
        val base = when {
            shortSide >= 2160 -> 40_000_000
            shortSide >= 1080 -> 12_000_000
            shortSide >= 720 -> 7_000_000
            else -> 3_500_000
        }
        return if (fps > 30) (base * 1.5).toInt() else base
    }

    /** Must be called from a coroutine; Transformer itself runs on the main looper. */
    suspend fun export(project: Project, shortSide: Int, fps: Int, onProgress: (Float) -> Unit): File {
        if (project.clips.isEmpty()) throw ExportFailedException("Add a clip before exporting")
        val (w, h) = CompositionBuilder.canvasSize(project.aspect, shortSide)
        val composition = withContext(Dispatchers.Default) {
            CompositionBuilder.build(project, RenderSpec(w, h, includeOverlays = true, fps = fps))
        }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 24 * 3600_000L }?.forEach { it.delete() }
        val out = File(dir, safeName(project.name) + "_" + System.currentTimeMillis() + ".mp4")

        return withContext(Dispatchers.Main) {
            coroutineScope {
                var transformer: Transformer? = null
                val holder = ProgressHolder()
                val poller = launch {
                    while (isActive) {
                        val t = transformer
                        if (t != null && t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                        delay(200)
                    }
                }
                try {
                    suspendCancellableCoroutine { cont ->
                        val encoder = DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrateFor(shortSide, fps)).build())
                            .setEnableFallback(true)
                            .build()
                        val tr = Transformer.Builder(context)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .setEncoderFactory(encoder)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (cont.isActive) cont.resume(Unit)
                                }

                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    if (cont.isActive) cont.resumeWithException(
                                        ExportFailedException("Export failed (${exportException.errorCodeName})", exportException),
                                    )
                                }
                            })
                            .build()
                        transformer = tr
                        cont.invokeOnCancellation { tr.cancel() }
                        tr.start(composition, out.path)
                    }
                    onProgress(1f)
                    out
                } catch (e: Throwable) {
                    out.delete()
                    throw e
                } finally {
                    poller.cancel()
                }
            }
        }
    }

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifEmpty { "video" }.take(40)
}

object GallerySaver {
    /** Copies [file] into the user's gallery (Movies/My Edits). Returns the resulting content/file Uri. */
    suspend fun save(context: Context, file: File, displayName: String): Uri = withContext(Dispatchers.IO) {
        val name = displayName.replace(Regex("[^A-Za-z0-9 _-]+"), "").trim().ifEmpty { "My Edits" } + " " + System.currentTimeMillis() + ".mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/My Edits")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw ExportFailedException("Could not create the gallery entry")
            try {
                (resolver.openOutputStream(uri) ?: throw ExportFailedException("Could not open the gallery entry")).use { o -> file.inputStream().use { it.copyTo(o) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null); throw e
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "My Edits").apply { mkdirs() }
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.path), arrayOf("video/mp4"), null)
            Uri.fromFile(dest)
        }
    }
}
