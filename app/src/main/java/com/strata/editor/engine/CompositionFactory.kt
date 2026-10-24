@file:Suppress("UnsafeOptInUsageError")

package com.strata.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.Size
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.strata.editor.model.Clip
import com.strata.editor.model.ClipKind
import com.strata.editor.model.Project
import com.strata.editor.model.Prop
import com.strata.editor.model.Track
import com.strata.editor.model.TrackKind
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns a [Project] into a Media3 [Composition].
 *
 * Layer model: one EditedMediaItemSequence per visible video layer. The compositor draws the first
 * sequence on top, and the first sequence also drives output timing, so sequence 0 is an invisible
 * full-length "clock" (a transparent still at the project frame rate). Then video layers top→bottom,
 * then audio-only sequences. Transforms and keyframes are read live from [ProjectRef], so moving or
 * animating a layer doesn't require rebuilding the composition.
 */
class ProjectRef(initial: Project) {
    private val ref = AtomicReference(initial)
    var value: Project
        get() = ref.get()
        set(v) = ref.set(v)
}

class CompositionFactory(private val context: Context) {

    private val clockUri: Uri by lazy {
        val f = File(context.filesDir, "clock.png")
        if (!f.exists()) {
            val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        Uri.fromFile(f)
    }

    /**
     * @param outW output width in pixels (already scaled for preview or export)
     * @param forExport use original files instead of proxies, and enforce the export frame rate
     * @param watermark draw the free-tier watermark
     */
    fun build(
        ref: ProjectRef,
        outW: Int,
        outH: Int,
        forExport: Boolean,
        watermark: Boolean,
        exportFps: Int = 0,
    ): Composition {
        val project = ref.value
        val total = max(project.durationUs, 200_000L)
        val sequences = mutableListOf<EditedMediaItemSequence>()
        val layerTracks = mutableListOf<String?>() // index = compositor input id

        // 0: timing clock.
        val clockItem = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(clockUri).setMimeType(MimeTypes.IMAGE_PNG)
                .setImageDurationMs(max(1L, total / 1000)).build()
        ).setDurationUs(total).setFrameRate(project.fps).build()
        sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItem(clockItem).build()
        layerTracks += null

        val visual = project.tracks.filter { (it.kind == TrackKind.VIDEO || it.kind == TrackKind.MAIN) && it.visible && it.clips.isNotEmpty() }
        for (t in visual) {
            sequences += videoSequence(t, total, outW, outH, forExport, project.fps)
            layerTracks += t.id
        }
        for (t in project.tracks.filter { it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty() }) {
            sequences += audioSequence(t, total)
        }

        val compositor = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = Size(outW, outH)

            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                val trackId = layerTracks.getOrNull(inputId) ?: return HIDDEN
                val track = ref.value.tracks.firstOrNull { it.id == trackId } ?: return HIDDEN
                if (!track.visible) return HIDDEN
                val (clip, start) = track.clipAt(presentationTimeUs) ?: return HIDDEN
                return settingsFor(clip, presentationTimeUs - start)
            }
        }

        val overlays = mutableListOf<TextureOverlay>()
        project.tracks.filter { it.kind == TrackKind.TEXT && it.visible }.forEach { t ->
            t.clips.forEach { c -> overlays += LiveTextOverlay(ref, c.id, outH) }
        }
        if (watermark) overlays += watermarkOverlay(outH)

        val compEffects = mutableListOf<Effect>()
        if (overlays.isNotEmpty()) compEffects += OverlayEffect(overlays)
        if (forExport && exportFps > 0) compEffects += FrameDropEffect.createDefaultFrameDropEffect(exportFps.toFloat())

        return Composition.Builder(sequences)
            .setVideoCompositorSettings(compositor)
            .setEffects(Effects(emptyList(), compEffects))
            .build()
    }

    private fun videoSequence(t: Track, total: Long, outW: Int, outH: Int, forExport: Boolean, fps: Int): EditedMediaItemSequence {
        val withAudio = !t.muted && t.clips.any { it.kind == ClipKind.VIDEO && it.hasAudio && it.volume > 0f }
        val types = if (withAudio) setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO) else setOf(C.TRACK_TYPE_VIDEO)
        val b = EditedMediaItemSequence.Builder(types)
        var cursor = 0L
        for ((clip, start) in t.placed()) {
            if (start > cursor) { b.addGap(start - cursor); cursor = start }
            b.addItem(visualItem(clip, outW, outH, forExport, fps, includeAudio = withAudio))
            cursor += clip.durationUs
        }
        if (cursor < total) b.addGap(total - cursor)
        return b.build()
    }

    private fun audioSequence(t: Track, total: Long): EditedMediaItemSequence {
        val b = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        var cursor = 0L
        for ((clip, start) in t.placed()) {
            if (start > cursor) { b.addGap(start - cursor); cursor = start }
            val eb = EditedMediaItem.Builder(clippedItem(Uri.parse(clip.uri), clip))
                .setDurationUs(clip.sourceDurationUs)
                .setRemoveVideo(true)
            if (clip.speed != 1f) eb.setSpeed(ConstantSpeed(clip.speed))
            eb.setEffects(Effects(volumeProcessors(clip.volume), emptyList()))
            b.addItem(eb.build())
            cursor += clip.durationUs
        }
        if (cursor < total) b.addGap(total - cursor)
        return b.build()
    }

    private fun clippedItem(uri: Uri, clip: Clip): MediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(clip.trimStartUs / 1000)
        if (clip.trimEndUs < clip.sourceDurationUs - 1000) clipping.setEndPositionMs(clip.trimEndUs / 1000)
        return MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping.build()).build()
    }

    private fun visualItem(clip: Clip, outW: Int, outH: Int, forExport: Boolean, fps: Int, includeAudio: Boolean): EditedMediaItem {
        val effects = mutableListOf<Effect>()
        val (fw, fh) = fitSize(clip.width, clip.height, outW, outH)
        effects += Presentation.createForWidthAndHeight(fw, fh, Presentation.LAYOUT_STRETCH_TO_FIT)
        if (clip.brightness != 0f) effects += Brightness(clip.brightness.coerceIn(-1f, 1f))
        if (clip.contrast != 0f) effects += Contrast(clip.contrast.coerceIn(-1f, 1f))
        if (clip.saturation != 0f) effects += HslAdjustment.Builder().adjustSaturation((clip.saturation * 100f).coerceIn(-100f, 100f)).build()

        return when (clip.kind) {
            ClipKind.IMAGE -> {
                val item = MediaItem.Builder().setUri(Uri.parse(clip.uri))
                    .setImageDurationMs(max(1L, clip.durationUs / 1000)).build()
                EditedMediaItem.Builder(item)
                    .setDurationUs(clip.durationUs)
                    .setFrameRate(fps)
                    .setEffects(Effects(emptyList(), effects))
                    .build()
            }
            else -> {
                val useProxy = !forExport && clip.proxyPath != null && File(clip.proxyPath).exists()
                val uri = if (useProxy) Uri.fromFile(File(clip.proxyPath!!)) else Uri.parse(clip.uri)
                val eb = EditedMediaItem.Builder(clippedItem(uri, clip))
                    .setDurationUs(clip.sourceDurationUs)
                    .setEffects(Effects(if (includeAudio) volumeProcessors(clip.volume) else emptyList(), effects))
                    .setRemoveAudio(!includeAudio || !clip.hasAudio)
                if (clip.speed != 1f) eb.setSpeed(ConstantSpeed(clip.speed))
                eb.build()
            }
        }
    }

    companion object {
        val HIDDEN: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()

        /** Largest even size with the clip's aspect ratio that fits inside the output. */
        fun fitSize(w: Int, h: Int, outW: Int, outH: Int): Pair<Int, Int> {
            if (w <= 0 || h <= 0) return outW to outH
            val s = min(outW.toFloat() / w, outH.toFloat() / h)
            val fw = max(2, ((w * s) / 2f).roundToInt() * 2)
            val fh = max(2, ((h * s) / 2f).roundToInt() * 2)
            return fw to fh
        }

        fun settingsFor(clip: Clip, localUs: Long): OverlaySettings {
            val s = clip.valueAt(Prop.SCALE, localUs).coerceAtLeast(0.01f)
            return StaticOverlaySettings.Builder()
                .setScale(s, s)
                .setRotationDegrees(-clip.valueAt(Prop.ROTATION, localUs))
                .setAlphaScale(clip.valueAt(Prop.OPACITY, localUs).coerceIn(0f, 1f))
                .setBackgroundFrameAnchor(
                    clip.valueAt(Prop.X, localUs).coerceIn(-3f, 3f),
                    clip.valueAt(Prop.Y, localUs).coerceIn(-3f, 3f),
                )
                .build()
        }

        fun volumeProcessors(volume: Float): List<AudioProcessor> {
            if (volume == 1f) return emptyList()
            val p = ChannelMixingAudioProcessor()
            for (n in 1..2) p.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(n, n).scaleBy(volume))
            for (n in 3..6) p.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(n, 2).scaleBy(volume))
            return listOf(p)
        }

        fun watermarkOverlay(outH: Int): TextureOverlay {
            val text = SpannableString("Made with Strata")
            val px = max(12, (outH * 0.032f).roundToInt())
            text.setSpan(AbsoluteSizeSpan(px), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(0xCCFFFFFF.toInt()), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            return TextOverlay.createStaticTextOverlay(
                text,
                StaticOverlaySettings.Builder()
                    .setOverlayFrameAnchor(1f, -1f)
                    .setBackgroundFrameAnchor(0.96f, -0.94f)
                    .build(),
            )
        }
    }
}

class ConstantSpeed(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}

/** A text layer that reads its content, style and animation from the live project. */
class LiveTextOverlay(
    private val ref: ProjectRef,
    private val clipId: String,
    private val outH: Int,
) : TextOverlay() {
    private var lastKey: String? = null
    private var lastText: SpannableString = SpannableString(" ")

    private fun current(): Pair<Clip, Long>? {
        val p = ref.value
        val (track, clip) = p.find(clipId) ?: return null
        if (!track.visible) return null
        return clip to p.startOf(clipId)
    }

    override fun getText(presentationTimeUs: Long): SpannableString {
        val (clip, _) = current() ?: return lastText
        val px = max(8, (clip.textSize * outH).roundToInt())
        val raw = clip.text.ifBlank { " " }
        val key = "$raw|$px|${clip.textColor}|${clip.textBold}"
        if (key != lastKey) {
            val s = SpannableString(raw)
            s.setSpan(AbsoluteSizeSpan(px), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(ForegroundColorSpan(clip.textColor), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (clip.textBold) s.setSpan(StyleSpan(Typeface.BOLD), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            lastText = s
            lastKey = key
        }
        return lastText
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val (clip, start) = current() ?: return CompositionFactory.HIDDEN
        if (presentationTimeUs < start || presentationTimeUs >= start + clip.durationUs) return CompositionFactory.HIDDEN
        return CompositionFactory.settingsFor(clip, presentationTimeUs - start)
    }
}
