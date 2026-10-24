package com.strata.editor.model

import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Visual stacking: TEXT tracks always render above video; VIDEO/MAIN tracks stack in list order. */
enum class TrackKind { MAIN, VIDEO, TEXT, AUDIO }

enum class ClipKind { VIDEO, IMAGE, AUDIO, TEXT }

/** Animatable properties. Position is in normalized frame coordinates (-1..1, centre = 0). */
enum class Prop { X, Y, SCALE, ROTATION, OPACITY }

data class Keyframe(val timeUs: Long, val value: Float)

fun newId(): String = UUID.randomUUID().toString().take(12)

data class Clip(
    val id: String = newId(),
    val kind: ClipKind,
    val uri: String = "",
    val name: String = "",
    val proxyPath: String? = null,
    val proxyState: ProxyState = ProxyState.NONE,
    val sourceDurationUs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val hasAudio: Boolean = false,
    /** Source in/out points. For images and text these are 0..displayDuration. */
    val trimStartUs: Long = 0,
    val trimEndUs: Long = sourceDurationUs,
    /** Timeline position. Ignored for clips on the MAIN track (they play back to back). */
    val startUs: Long = 0,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val keyframes: Map<Prop, List<Keyframe>> = emptyMap(),
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val text: String = "",
    val textColor: Int = 0xFFFFFFFF.toInt(),
    /** Text size as a fraction of the output frame height. */
    val textSize: Float = 0.08f,
    val textBold: Boolean = true,
) {
    val isVisual: Boolean get() = kind != ClipKind.AUDIO
    val hasTimeSource: Boolean get() = kind == ClipKind.VIDEO || kind == ClipKind.AUDIO

    /** Length on the timeline. */
    val durationUs: Long
        get() {
            val src = max(1L, trimEndUs - trimStartUs)
            return if (hasTimeSource) max(1L, (src / speed).toLong()) else src
        }

    fun base(p: Prop): Float = when (p) {
        Prop.X -> x
        Prop.Y -> y
        Prop.SCALE -> scale
        Prop.ROTATION -> rotation
        Prop.OPACITY -> opacity
    }

    fun withBase(p: Prop, v: Float): Clip = when (p) {
        Prop.X -> copy(x = v)
        Prop.Y -> copy(y = v)
        Prop.SCALE -> copy(scale = v)
        Prop.ROTATION -> copy(rotation = v)
        Prop.OPACITY -> copy(opacity = v)
    }

    /** Value of [p] at [localUs] (time since this clip started on the timeline). */
    fun valueAt(p: Prop, localUs: Long): Float {
        val keys = keyframes[p]
        if (keys.isNullOrEmpty()) return base(p)
        return Interp.sample(keys, localUs)
    }

    fun hasKeyframes(): Boolean = keyframes.values.any { it.isNotEmpty() }

    fun keyframeTimes(): List<Long> = keyframes.values.flatten().map { it.timeUs }.distinct().sorted()
}

enum class ProxyState { NONE, PENDING, READY, FAILED }

data class Track(
    val id: String = newId(),
    val kind: TrackKind,
    val name: String,
    val clips: List<Clip> = emptyList(),
    val visible: Boolean = true,
    val muted: Boolean = false,
    val locked: Boolean = false,
) {
    /** Clips with their timeline start, in timeline order. */
    fun placed(): List<Pair<Clip, Long>> {
        return if (kind == TrackKind.MAIN) {
            var t = 0L
            clips.map { c -> (c to t).also { t += c.durationUs } }
        } else {
            clips.sortedBy { it.startUs }.map { it to it.startUs }
        }
    }

    val endUs: Long get() = placed().maxOfOrNull { it.second + it.first.durationUs } ?: 0L

    fun clipAt(timeUs: Long): Pair<Clip, Long>? =
        placed().firstOrNull { (c, s) -> timeUs >= s && timeUs < s + c.durationUs }

    fun isFree(startUs: Long, durUs: Long, ignoreId: String? = null): Boolean =
        placed().none { (c, s) ->
            c.id != ignoreId && startUs < s + c.durationUs && s < startUs + durUs
        }
}

data class Project(
    val id: String = newId(),
    val name: String,
    val canvasW: Int = 1920,
    val canvasH: Int = 1080,
    val fps: Int = 30,
    val tracks: List<Track> = listOf(Track(kind = TrackKind.MAIN, name = "Main")),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val main: Track get() = tracks.first { it.kind == TrackKind.MAIN }
    val durationUs: Long get() = tracks.maxOfOrNull { it.endUs } ?: 0L
    val layerCount: Int get() = tracks.count { it.clips.isNotEmpty() }
    val overlayTrackCount: Int get() = tracks.count { it.kind == TrackKind.VIDEO }
    val isEmpty: Boolean get() = tracks.all { it.clips.isEmpty() }

    /** Short side used to describe the canvas, e.g. 1080 or 2160. */
    val shortSide: Int get() = min(canvasW, canvasH)

    fun find(clipId: String): Pair<Track, Clip>? {
        for (t in tracks) for (c in t.clips) if (c.id == clipId) return t to c
        return null
    }

    fun startOf(clipId: String): Long {
        val (t, _) = find(clipId) ?: return 0L
        return t.placed().first { it.first.id == clipId }.second
    }

    fun mapTrack(trackId: String, f: (Track) -> Track): Project =
        copy(tracks = tracks.map { if (it.id == trackId) f(it) else it })

    fun mapClip(clipId: String, f: (Clip) -> Clip): Project =
        copy(tracks = tracks.map { t ->
            if (t.clips.any { it.id == clipId }) t.copy(clips = t.clips.map { if (it.id == clipId) f(it) else it }) else t
        })

    fun removeClip(clipId: String): Project {
        val out = tracks.map { t -> t.copy(clips = t.clips.filterNot { it.id == clipId }) }
            .filter { it.kind == TrackKind.MAIN || it.clips.isNotEmpty() }
        return copy(tracks = out)
    }

    /**
     * Puts [clip] at [startUs] on the first track of [kind] where it fits, or on a new track
     * stacked at the top of that kind.
     */
    fun placeOnFreeTrack(kind: TrackKind, clip: Clip, startUs: Long): Project {
        val placedClip = clip.copy(startUs = max(0L, startUs))
        val existing = tracks.firstOrNull { it.kind == kind && it.isFree(placedClip.startUs, placedClip.durationUs) }
        if (existing != null) {
            return mapTrack(existing.id) { it.copy(clips = it.clips + placedClip) }
        }
        val n = tracks.count { it.kind == kind } + 1
        val name = when (kind) {
            TrackKind.VIDEO -> "Layer $n"
            TrackKind.TEXT -> "Text $n"
            TrackKind.AUDIO -> "Audio $n"
            TrackKind.MAIN -> "Main"
        }
        val newTrack = Track(kind = kind, name = name, clips = listOf(placedClip))
        return copy(tracks = insertTrack(newTrack))
    }

    /** Keeps the order: TEXT tracks, then VIDEO/MAIN stack, then AUDIO tracks. New tracks go on top of their group. */
    private fun insertTrack(t: Track): List<Track> {
        val list = tracks.toMutableList()
        val idx = when (t.kind) {
            TrackKind.TEXT -> 0
            TrackKind.VIDEO -> list.indexOfFirst { it.kind == TrackKind.VIDEO || it.kind == TrackKind.MAIN }.coerceAtLeast(0)
            TrackKind.AUDIO -> list.indexOfFirst { it.kind == TrackKind.AUDIO }.let { if (it < 0) list.size else it }
            TrackKind.MAIN -> list.size
        }
        list.add(idx, t)
        return list
    }

    fun touched(): Project = copy(updatedAt = System.currentTimeMillis())
}

object Interp {
    /** Smooth (ease in-out) interpolation between sorted keyframes. */
    fun sample(keys: List<Keyframe>, t: Long): Float {
        if (keys.size == 1) return keys[0].value
        val sorted = keys.sortedBy { it.timeUs }
        if (t <= sorted.first().timeUs) return sorted.first().value
        if (t >= sorted.last().timeUs) return sorted.last().value
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (t >= a.timeUs && t <= b.timeUs) {
                val span = (b.timeUs - a.timeUs).coerceAtLeast(1L)
                var f = (t - a.timeUs).toFloat() / span
                f = f * f * (3f - 2f * f)
                return a.value + (b.value - a.value) * f
            }
        }
        return sorted.last().value
    }
}

/** Edits that touch clips, keyframes and timing. All return new immutable values. */
object Edits {
    private const val KEY_TOLERANCE_US = 40_000L

    /**
     * Sets [p] to [v] at [localUs]. If the property is animated, the keyframe at that time is
     * updated (or a new one added, CapCut-style auto-keying); otherwise the static value changes.
     */
    fun setProp(clip: Clip, p: Prop, v: Float, localUs: Long): Clip {
        val keys = clip.keyframes[p]
        if (keys.isNullOrEmpty()) return clip.withBase(p, v)
        val near = keys.indexOfFirst { kotlin.math.abs(it.timeUs - localUs) <= KEY_TOLERANCE_US }
        val newKeys = if (near >= 0) keys.toMutableList().also { it[near] = Keyframe(it[near].timeUs, v) }
        else (keys + Keyframe(localUs, v)).sortedBy { it.timeUs }
        return clip.copy(keyframes = clip.keyframes + (p to newKeys))
    }

    /** Adds a keyframe for every visual property at [localUs], using the current values. */
    fun addKeyframeAll(clip: Clip, localUs: Long): Clip {
        var c = clip
        for (p in Prop.entries) {
            val v = clip.valueAt(p, localUs)
            val keys = (c.keyframes[p] ?: emptyList()).filterNot { kotlin.math.abs(it.timeUs - localUs) <= KEY_TOLERANCE_US }
            c = c.copy(keyframes = c.keyframes + (p to (keys + Keyframe(localUs, v)).sortedBy { it.timeUs }))
        }
        return c
    }

    fun hasKeyAt(clip: Clip, localUs: Long): Boolean =
        clip.keyframes.values.flatten().any { kotlin.math.abs(it.timeUs - localUs) <= KEY_TOLERANCE_US }

    fun removeKeyframesAt(clip: Clip, localUs: Long): Clip {
        val out = clip.keyframes.mapValues { (_, ks) -> ks.filterNot { kotlin.math.abs(it.timeUs - localUs) <= KEY_TOLERANCE_US } }
            .filterValues { it.isNotEmpty() }
        // Bake values back into the base for props whose animation disappeared.
        var c = clip.copy(keyframes = out)
        for (p in Prop.entries) if (out[p] == null && clip.keyframes[p] != null) c = c.withBase(p, clip.valueAt(p, localUs))
        return c
    }

    /** Splits a clip at timeline time [atUs]. Returns the project and the id of the right-hand part. */
    fun split(project: Project, clipId: String, atUs: Long): Pair<Project, String>? {
        val (track, clip) = project.find(clipId) ?: return null
        val start = project.startOf(clipId)
        val local = atUs - start
        if (local <= 30_000 || local >= clip.durationUs - 30_000) return null
        val srcSplit = if (clip.hasTimeSource) clip.trimStartUs + (local * clip.speed).toLong() else clip.trimStartUs + local
        val leftKeys = clip.keyframes.mapValues { (_, ks) -> ks.filter { it.timeUs <= local } }.filterValues { it.isNotEmpty() }
        val rightKeys = clip.keyframes.mapValues { (_, ks) -> ks.filter { it.timeUs >= local }.map { Keyframe(it.timeUs - local, it.value) } }
            .filterValues { it.isNotEmpty() }
        var left = clip.copy(trimEndUs = srcSplit, keyframes = leftKeys)
        var right = clip.copy(id = newId(), trimStartUs = srcSplit, startUs = atUs, keyframes = rightKeys)
        // Keep animated props continuous at the cut.
        for (p in Prop.entries) {
            val v = clip.valueAt(p, local)
            if (clip.keyframes[p] != null) {
                if (leftKeys[p] == null) left = left.withBase(p, v)
                if (rightKeys[p] == null) right = right.withBase(p, v)
            }
        }
        if (!clip.hasTimeSource) {
            left = left.copy(trimStartUs = 0, trimEndUs = local)
            right = right.copy(trimStartUs = 0, trimEndUs = clip.durationUs - local, sourceDurationUs = max(clip.sourceDurationUs, clip.durationUs))
        }
        val p = project.mapTrack(track.id) { t ->
            val list = t.clips.toMutableList()
            val i = list.indexOfFirst { it.id == clipId }
            list[i] = left
            list.add(i + 1, right)
            t.copy(clips = list)
        }
        return p to right.id
    }

    fun duplicate(project: Project, clipId: String): Pair<Project, String>? {
        val (track, clip) = project.find(clipId) ?: return null
        val copy = clip.copy(id = newId())
        return if (track.kind == TrackKind.MAIN) {
            project.mapTrack(track.id) { t ->
                val list = t.clips.toMutableList()
                list.add(list.indexOfFirst { it.id == clipId } + 1, copy)
                t.copy(clips = list)
            } to copy.id
        } else {
            val start = project.startOf(clipId) + clip.durationUs
            if (track.isFree(start, clip.durationUs)) {
                project.mapTrack(track.id) { it.copy(clips = it.clips + copy.copy(startUs = start)) } to copy.id
            } else {
                project.placeOnFreeTrack(track.kind, copy, start) to copy.id
            }
        }
    }

    /** Moves a main-track clip one slot left (-1) or right (+1). */
    fun reorderMain(project: Project, clipId: String, dir: Int): Project =
        project.mapTrack(project.main.id) { t ->
            val list = t.clips.toMutableList()
            val i = list.indexOfFirst { it.id == clipId }
            val j = i + dir
            if (i < 0 || j < 0 || j >= list.size) return@mapTrack t
            val tmp = list[i]; list[i] = list[j]; list[j] = tmp
            t.copy(clips = list)
        }

    /** Moves an overlay/text/audio clip in time, staying clear of its neighbours. */
    fun moveTo(project: Project, clipId: String, newStartUs: Long): Project {
        val (track, clip) = project.find(clipId) ?: return project
        if (track.kind == TrackKind.MAIN) return project
        val s = max(0L, newStartUs)
        if (!track.isFree(s, clip.durationUs, ignoreId = clipId)) return project
        return project.mapClip(clipId) { it.copy(startUs = s) }
    }

    /** Trims the in point by [deltaUs] of timeline time (positive = shorter from the left). */
    fun trimStart(project: Project, clipId: String, deltaUs: Long): Project {
        val (track, clip) = project.find(clipId) ?: return project
        val minDur = 100_000L
        if (clip.hasTimeSource) {
            val srcDelta = (deltaUs * clip.speed).toLong()
            val newIn = (clip.trimStartUs + srcDelta).coerceIn(0L, clip.trimEndUs - (minDur * clip.speed).toLong())
            val applied = ((newIn - clip.trimStartUs) / clip.speed).toLong()
            val newStart = clip.startUs + applied
            if (track.kind != TrackKind.MAIN && (newStart < 0 || !track.isFree(newStart, clip.durationUs - applied, clipId))) return project
            return project.mapClip(clipId) { it.copy(trimStartUs = newIn, startUs = if (track.kind == TrackKind.MAIN) it.startUs else newStart, keyframes = shiftKeys(it.keyframes, -applied)) }
        } else {
            val newDur = (clip.durationUs - deltaUs).coerceAtLeast(minDur)
            val applied = clip.durationUs - newDur
            val newStart = clip.startUs + applied
            if (track.kind != TrackKind.MAIN && (newStart < 0 || !track.isFree(newStart, newDur, clipId))) return project
            return project.mapClip(clipId) {
                it.copy(trimStartUs = 0, trimEndUs = newDur, sourceDurationUs = max(it.sourceDurationUs, newDur),
                    startUs = if (track.kind == TrackKind.MAIN) it.startUs else newStart, keyframes = shiftKeys(it.keyframes, -applied))
            }
        }
    }

    /** Trims the out point by [deltaUs] of timeline time (positive = longer). */
    fun trimEnd(project: Project, clipId: String, deltaUs: Long): Project {
        val (track, clip) = project.find(clipId) ?: return project
        val minDur = 100_000L
        if (clip.hasTimeSource) {
            val newOut = (clip.trimEndUs + (deltaUs * clip.speed).toLong())
                .coerceIn(clip.trimStartUs + (minDur * clip.speed).toLong(), clip.sourceDurationUs)
            val newDur = ((newOut - clip.trimStartUs) / clip.speed).toLong()
            if (track.kind != TrackKind.MAIN && !track.isFree(clip.startUs, newDur, clipId)) return project
            return project.mapClip(clipId) { it.copy(trimEndUs = newOut) }
        } else {
            val newDur = (clip.durationUs + deltaUs).coerceAtLeast(minDur)
            if (track.kind != TrackKind.MAIN && !track.isFree(clip.startUs, newDur, clipId)) return project
            return project.mapClip(clipId) { it.copy(trimStartUs = 0, trimEndUs = newDur, sourceDurationUs = max(it.sourceDurationUs, newDur)) }
        }
    }

    private fun shiftKeys(k: Map<Prop, List<Keyframe>>, by: Long): Map<Prop, List<Keyframe>> =
        if (k.isEmpty() || by == 0L) k else k.mapValues { (_, ks) -> ks.map { Keyframe(max(0L, it.timeUs + by), it.value) } }

    fun setSpeed(project: Project, clipId: String, speed: Float): Project {
        val (track, clip) = project.find(clipId) ?: return project
        if (!clip.hasTimeSource) return project
        val updated = clip.copy(speed = speed)
        if (track.kind != TrackKind.MAIN && !track.isFree(clip.startUs, updated.durationUs, clipId)) {
            // Not enough room: move the clip to its own layer.
            return project.removeClip(clipId).placeOnFreeTrack(track.kind, updated, clip.startUs)
        }
        return project.mapClip(clipId) { updated }
    }
}
