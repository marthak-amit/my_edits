package com.myedits.app.util

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import com.myedits.app.data.*
import com.myedits.app.engine.ProjectOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

data class AudioInfo(val file: File, val label: String, val durationMs: Long)

/**
 * Copies picked media into app storage (picker URIs are only valid temporarily, and drafts must
 * survive restarts) and reads the metadata the timeline needs.
 */
class MediaImporter(private val context: Context, private val repo: ProjectRepository) {

    class ImportException(message: String) : Exception(message)

    suspend fun importVisual(projectId: String, uris: List<Uri>): List<Clip> = withContext(Dispatchers.IO) {
        uris.map { importOne(projectId, it) }
    }

    private fun importOne(projectId: String, uri: Uri): Clip {
        val cr = context.contentResolver
        val mime = cr.getType(uri).orEmpty()
        val isImage = mime.startsWith("image/")
        val isVideo = mime.startsWith("video/")
        if (!isImage && !isVideo) throw ImportException("Unsupported file type: ${mime.ifEmpty { "unknown" }}")
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (isImage) "jpg" else "mp4"
        val dest = repo.newMediaFile(projectId, ext)
        try {
            (cr.openInputStream(uri) ?: throw ImportException("Cannot open the selected file")).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            dest.delete()
            throw ImportException("Could not read the file: ${e.message}")
        }
        return clipFromFile(dest, isImage)
    }

    fun clipFromFile(file: File, isImage: Boolean): Clip =
        if (isImage) {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, b)
            var w = b.outWidth; var h = b.outHeight
            val orientation = runCatching {
                ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270) { val t = w; w = h; h = t }
            if (w <= 0 || h <= 0) { file.delete(); throw ImportException("That image could not be decoded") }
            ProjectOps.makeImageClip(file.path, w, h)
        } else {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.path)
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                if (dur <= 0 || w <= 0 || h <= 0) { file.delete(); throw ImportException("That video could not be read") }
                ProjectOps.makeVideoClip(file.path, dur, w, h)
            } catch (e: ImportException) {
                throw e
            } catch (e: Exception) {
                file.delete()
                throw ImportException("That video could not be read")
            } finally {
                runCatching { r.release() }
            }
        }

    /** Imports an audio file (music, etc.) chosen by the user. */
    suspend fun importAudio(projectId: String, uri: Uri): AudioInfo = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val mime = cr.getType(uri).orEmpty()
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "m4a"
        val dest = repo.newMediaFile(projectId, ext)
        try {
            (cr.openInputStream(uri) ?: throw ImportException("Cannot open the selected file")).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            dest.delete(); throw ImportException("Could not read the file: ${e.message}")
        }
        val name = queryName(uri) ?: "Audio"
        describeAudio(dest, name.substringBeforeLast('.'))
    }

    fun describeAudio(file: File, label: String): AudioInfo {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            if (dur <= 0 || hasAudio == "no") { file.delete(); throw ImportException("That file has no playable audio") }
            return AudioInfo(file, label, dur)
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            file.delete(); throw ImportException("That audio file could not be read")
        } finally {
            runCatching { r.release() }
        }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    /** Copies the audio track of a video clip into a standalone .m4a without re-encoding. */
    suspend fun extractAudio(projectId: String, clip: Clip): AudioInfo = withContext(Dispatchers.IO) {
        if (clip.isImage) throw ImportException("Photos have no audio")
        val out = repo.newMediaFile(projectId, "m4a")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(clip.path)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
            }
            if (track < 0) throw ImportException("This clip has no audio track")
            val fmt = extractor.getTrackFormat(track)
            if (fmt.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) throw ImportException("Only AAC audio can be extracted")
            extractor.selectTrack(track)
            extractor.seekTo(clip.inMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val dst = muxer.addTrack(fmt)
            muxer.start()
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = android.media.MediaCodec.BufferInfo()
            var first = -1L
            while (true) {
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                val t = extractor.sampleTime
                if (t > clip.outMs * 1000) break
                if (first < 0) first = t
                info.set(0, size, t - first, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(dst, buf, info)
                extractor.advance()
            }
            muxer.stop()
            describeAudio(out, "Audio from video")
        } catch (e: ImportException) {
            out.delete(); throw e
        } catch (e: Exception) {
            out.delete(); throw ImportException("Could not extract audio: ${e.message}")
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}
