@file:Suppress("UnsafeOptInUsageError")

package com.strata.editor.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.strata.editor.model.Clip
import com.strata.editor.model.Project
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Codec(val label: String, val mime: String) {
    H264("H.264", MimeTypes.VIDEO_H264),
    HEVC("HEVC", MimeTypes.VIDEO_H265),
    AV1("AV1", MimeTypes.VIDEO_AV1),
}

data class ExportSettings(
    val shortSide: Int,
    val fps: Int,
    val codec: Codec,
    /** Bits per second; 0 = automatic. */
    val bitrate: Int,
    val watermark: Boolean,
)

object ExportMath {
    /** Output size for a canvas, keeping its aspect ratio, with the short side = [shortSide]. */
    fun outputSize(p: Project, shortSide: Int): Pair<Int, Int> {
        val landscape = p.canvasW >= p.canvasH
        val ratio = max(p.canvasW, p.canvasH).toFloat() / min(p.canvasW, p.canvasH)
        val s = shortSide
        val long = ((s * ratio) / 2f).roundToInt() * 2
        return if (landscape) long to s else s to long
    }

    fun autoBitrate(w: Int, h: Int, fps: Int, codec: Codec): Int {
        val bpp = when (codec) { Codec.H264 -> 0.1; Codec.HEVC -> 0.07; Codec.AV1 -> 0.055 }
        return (w.toDouble() * h * fps * bpp).toInt().coerceIn(1_000_000, 120_000_000)
    }

    fun estimateBytes(bitrate: Int, durationUs: Long): Long =
        ((bitrate + 192_000L) / 8.0 * (durationUs / 1_000_000.0)).toLong()
}

/** Renders a project to an MP4 and saves it to Movies/Strata. */
class Exporter(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var poll: Runnable? = null

    fun start(
        project: Project,
        settings: ExportSettings,
        onProgress: (Int) -> Unit,
        onDone: (Uri?, String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val (w, h) = ExportMath.outputSize(project, settings.shortSide)
        val ref = ProjectRef(project)
        val composition = CompositionFactory(context).build(
            ref, w, h, forExport = true, watermark = settings.watermark, exportFps = settings.fps,
        )
        val bitrate = if (settings.bitrate > 0) settings.bitrate else ExportMath.autoBitrate(w, h, settings.fps, settings.codec)
        val out = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val encoder = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .setEnableFallback(true)
            .build()
        val t = Transformer.Builder(context)
            .setVideoMimeType(settings.codec.mime)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoder)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopPolling()
                    transformer = null
                    val name = "${project.name.replace(Regex("[^A-Za-z0-9 _-]"), "").ifBlank { "Strata" }}_${w}x$h.mp4"
                    val saved = saveToGallery(out, name)
                    onDone(saved, name)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    stopPolling()
                    transformer = null
                    out.delete()
                    onError(friendly(exportException))
                }
            })
            .build()
        transformer = t
        t.start(composition, out.absolutePath)
        val holder = ProgressHolder()
        poll = object : Runnable {
            override fun run() {
                val tr = transformer ?: return
                if (tr.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                main.postDelayed(this, 250)
            }
        }
        main.post(poll!!)
    }

    fun cancel() {
        stopPolling()
        transformer?.cancel()
        transformer = null
    }

    private fun stopPolling() {
        poll?.let { main.removeCallbacks(it) }
        poll = null
    }

    private fun friendly(e: ExportException): String {
        val code = e.errorCodeName
        return when {
            code.contains("DECOD", true) -> "This phone couldn't decode one of the clips (often 8K on non-flagship chips). Try fewer layers playing at once or a smaller clip."
            code.contains("ENCOD", true) -> "This phone's encoder can't handle these settings. Try a lower resolution, frame rate, or H.264."
            code.contains("IO", true) -> "A source file couldn't be read. It may have been moved or deleted."
            else -> "Export failed ($code). ${e.message ?: ""}"
        }
    }

    private fun saveToGallery(file: File, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Strata")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { os -> file.inputStream().use { it.copyTo(os) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}

/**
 * Makes small H.264 copies of 4K/8K clips so the editor previews smoothly.
 * Export always reads the originals. Jobs run one at a time on the main thread looper.
 */
class ProxyMaker(private val context: Context) {
    private val dir = File(context.filesDir, "proxies").apply { mkdirs() }
    private val queue = ArrayDeque<Pair<Clip, (String?) -> Unit>>()
    private var running: Transformer? = null

    fun proxyFile(clip: Clip): File = File(dir, "${clip.uri.hashCode().toUInt()}_720.mp4")

    fun enqueue(clip: Clip, onResult: (String?) -> Unit) {
        val f = proxyFile(clip)
        if (f.exists() && f.length() > 0) { onResult(f.absolutePath); return }
        if (queue.any { it.first.uri == clip.uri }) {
            queue.addLast(clip to onResult)
            return
        }
        queue.addLast(clip to onResult)
        if (running == null) next()
    }

    private fun next() {
        val (clip, _) = queue.firstOrNull() ?: return
        val f = proxyFile(clip)
        if (f.exists() && f.length() > 0) { finish(clip.uri, f.absolutePath); return }
        val tmp = File(dir, f.name + ".part")
        tmp.delete()
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(clip.uri)))
            .setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(720))))
            .build()
        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    running = null
                    tmp.renameTo(f)
                    finish(clip.uri, f.absolutePath)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    running = null
                    tmp.delete()
                    finish(clip.uri, null)
                }
            })
            .build()
        running = t
        t.start(item, tmp.absolutePath)
    }

    private fun finish(uri: String, path: String?) {
        val done = queue.filter { it.first.uri == uri }
        queue.removeAll { it.first.uri == uri }
        done.forEach { it.second(path) }
        next()
    }

    fun cancelAll() {
        running?.cancel()
        running = null
        queue.clear()
    }
}
