package com.myedits.app.util

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.media3.common.util.UnstableApi
import com.myedits.app.data.AspectRatio
import com.myedits.app.engine.CompositionBuilder

object Device {
    /** True if some H.264 encoder on this device accepts frames of the given size. */
    fun canEncodeAvc(width: Int, height: Int): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .any { info ->
                val vc: MediaCodecInfo.VideoCapabilities? = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                vc != null && (vc.isSizeSupported(width, height) || vc.isSizeSupported(height, width))
            }
    }.getOrDefault(true)

    @UnstableApi
    fun supportsQuality(aspect: AspectRatio, shortSide: Int): Boolean {
        val (w, h) = CompositionBuilder.canvasSize(aspect, shortSide)
        return canEncodeAvc(w, h)
    }
}
