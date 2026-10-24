package com.strata.editor.data

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.strata.editor.model.Clip
import com.strata.editor.model.ClipKind
import kotlin.math.max
import kotlin.math.min

/** Reads what we need to know about an imported file. */
object MediaProbe {
    const val DEFAULT_IMAGE_US = 3_000_000L
    /** Images and text can be stretched this long. */
    const val STILL_MAX_US = 3_600_000_000L

    fun persist(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        var name = uri.lastPathSegment ?: "clip"
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
        }
        return name
    }

    fun probe(context: Context, uri: Uri): Clip? {
        val mime = context.contentResolver.getType(uri) ?: ""
        val name = displayName(context, uri)
        return when {
            mime.startsWith("image/") -> probeImage(context, uri, name)
            mime.startsWith("audio/") -> probeAv(context, uri, name, ClipKind.AUDIO)
            else -> probeAv(context, uri, name, ClipKind.VIDEO)
        }
    }

    private fun probeImage(context: Context, uri: Uri, name: String): Clip? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        if (opts.outWidth <= 0) return null
        return Clip(
            kind = ClipKind.IMAGE, uri = uri.toString(), name = name,
            sourceDurationUs = STILL_MAX_US, trimStartUs = 0, trimEndUs = DEFAULT_IMAGE_US,
            width = opts.outWidth, height = opts.outHeight,
        )
    }

    private fun probeAv(context: Context, uri: Uri, name: String, guess: ClipKind): Clip? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val kind = if (hasVideo && guess != ClipKind.AUDIO) ClipKind.VIDEO else ClipKind.AUDIO
            if (kind == ClipKind.AUDIO && !hasAudio) return null
            val us = durMs * 1000
            return Clip(
                kind = kind, uri = uri.toString(), name = name,
                sourceDurationUs = us, trimStartUs = 0, trimEndUs = us,
                width = w, height = h, hasAudio = hasAudio,
            )
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { r.release() }
        }
    }
}

/** What this phone's video hardware can do. */
object DeviceCaps {
    data class Caps(
        val decode8k: Boolean,
        val decode4k: Boolean,
        val hevcEncoder: Boolean,
        val av1Encoder: Boolean,
        val maxDecoders: Int,
    )

    @Volatile private var cached: Caps? = null

    fun get(): Caps = cached ?: compute().also { cached = it }

    private fun compute(): Caps {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        fun decoderSupports(w: Int, h: Int): Boolean = codecs.any { info ->
            !info.isEncoder && info.supportedTypes.any { t ->
                (t == MediaFormat.MIMETYPE_VIDEO_HEVC || t == MediaFormat.MIMETYPE_VIDEO_AVC) &&
                    runCatching { info.getCapabilitiesForType(t).videoCapabilities.isSizeSupported(w, h) }.getOrDefault(false)
            }
        }
        val hevcEnc = codecs.any { it.isEncoder && it.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_HEVC) }
        val av1Enc = codecs.any { it.isEncoder && it.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AV1) }
        val maxDec = codecs.filter { !it.isEncoder && it.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC) }
            .maxOfOrNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).maxSupportedInstances }.getOrDefault(1) } ?: 1
        return Caps(
            decode8k = decoderSupports(7680, 4320),
            decode4k = decoderSupports(3840, 2160),
            hevcEncoder = hevcEnc,
            av1Encoder = av1Enc,
            maxDecoders = maxDec,
        )
    }

    /** True if some hardware encoder for [mime] can do [w]x[h] at [fps] (either orientation). */
    fun canEncode(mime: String, w: Int, h: Int, fps: Int): Boolean {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        return codecs.any { info ->
            info.isEncoder && info.supportedTypes.contains(mime) && runCatching {
                val vc: MediaCodecInfo.VideoCapabilities = info.getCapabilitiesForType(mime).videoCapabilities
                vc.areSizeAndRateSupported(w, h, fps.toDouble()) || vc.areSizeAndRateSupported(h, w, fps.toDouble())
            }.getOrDefault(false)
        }
    }

    fun needsProxy(c: Clip): Boolean = c.kind == ClipKind.VIDEO && max(c.width, c.height) > 2600

    fun canDecode(c: Clip): Boolean {
        if (c.kind != ClipKind.VIDEO) return true
        val long = max(c.width, c.height)
        val short = min(c.width, c.height)
        return when {
            long > 4096 || short > 2160 -> get().decode8k
            else -> true
        }
    }
}

/** Pro mode switch. Free while in testing; Play billing can gate this later. */
class ProStore(context: Context) {
    private val prefs = context.getSharedPreferences("strata", Context.MODE_PRIVATE)
    var pro: Boolean
        get() = prefs.getBoolean("pro", false)
        set(v) { prefs.edit().putBoolean("pro", v).apply() }
}

/** What Free and Pro allow. */
object Tier {
    const val FREE_OVERLAY_LAYERS = 3
    const val FREE_MAX_SHORT_SIDE = 1080
    const val FREE_MAX_FPS = 30
    const val PRO_MAX_SHORT_SIDE = 2160
    const val PRO_MAX_FPS = 60
    val FREE_SPEED = 0.5f..2f
    val PRO_SPEED = 0.1f..10f
}
