@file:Suppress("UnsafeOptInUsageError")

package com.strata.editor.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import com.strata.editor.data.DeviceCaps
import com.strata.editor.data.MediaProbe
import com.strata.editor.data.ProjectStore
import com.strata.editor.data.Tier
import com.strata.editor.engine.CompositionFactory
import com.strata.editor.engine.ProjectRef
import com.strata.editor.engine.ProxyMaker
import com.strata.editor.model.Clip
import com.strata.editor.model.ClipKind
import com.strata.editor.model.Edits
import com.strata.editor.model.Project
import com.strata.editor.model.Prop
import com.strata.editor.model.ProxyState
import com.strata.editor.model.Track
import com.strata.editor.model.TrackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Target { MAIN, OVERLAY, AUDIO }

class EditorController(
    private val context: Context,
    initial: Project,
    private val store: ProjectStore,
    private val proxies: ProxyMaker,
    private val scope: CoroutineScope,
    pro: Boolean,
) {
    var project by mutableStateOf(initial)
        private set
    var selectedId by mutableStateOf<String?>(null)
    var positionUs by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var proPrompt by mutableStateOf<String?>(null)
    var undoCount by mutableIntStateOf(0)
        private set
    var redoCount by mutableIntStateOf(0)
        private set
    var pro by mutableStateOf(pro)
        private set

    private val ref = ProjectRef(initial)
    private val factory = CompositionFactory(context)
    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private var lastKey = ""
    private var rebuildJob: Job? = null
    private var redrawJob: Job? = null
    private var saveJob: Job? = null
    private var tickJob: Job? = null
    private var released = false
    private var lastGestureMs = 0L

    val player: CompositionPlayer = CompositionPlayer.Builder(context)
        .setVideoGraphFactory(MultipleInputVideoGraph.Factory())
        .build()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) startTicker() else tickJob?.cancel()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    playing = false
                    positionUs = project.durationUs
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                message = "Preview problem: ${error.errorCodeName}. Too many 4K/8K layers at once can exceed this phone's video decoders."
            }
        })
        rebuildNow()
        // Re-attach or make proxies for heavy clips.
        project.tracks.flatMap { it.clips }.filter { DeviceCaps.needsProxy(it) && it.proxyState != ProxyState.READY }
            .forEach { requestProxy(it) }
    }

    // ---- derived ----

    val selected: Pair<Track, Clip>? get() = selectedId?.let { project.find(it) }
    val selectedClip: Clip? get() = selected?.second
    val selectedStartUs: Long get() = selectedId?.let { project.startOf(it) } ?: 0L
    val selectedLocalUs: Long
        get() {
            val c = selectedClip ?: return 0L
            return (positionUs - selectedStartUs).coerceIn(0L, c.durationUs)
        }

    /** Preview render size: canvas aspect, short side at most 720 px. */
    private fun previewSize(p: Project): Pair<Int, Int> {
        val short = min(p.canvasW, p.canvasH)
        val s = min(1f, 720f / short)
        fun even(v: Float) = max(2, (v / 2f).roundToInt() * 2)
        return even(p.canvasW * s) to even(p.canvasH * s)
    }

    // ---- state changes ----

    fun beginEdit() = pushUndo()

    fun commit(p: Project, undoable: Boolean = true) {
        if (undoable) pushUndo()
        apply(p)
    }

    private fun pushUndo() {
        undoStack.addLast(project)
        if (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
        undoCount = undoStack.size
        redoCount = 0
    }

    private fun apply(p: Project) {
        project = p.touched()
        ref.value = project
        if (selectedId != null && project.find(selectedId!!) == null) selectedId = null
        scheduleSave()
        refresh()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(project)
        undoCount = undoStack.size; redoCount = redoStack.size
        apply(prev)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(project)
        undoCount = undoStack.size; redoCount = redoStack.size
        apply(next)
    }

    fun updatePro(value: Boolean) {
        if (pro == value) return
        pro = value
        refresh()
    }

    // ---- preview ----

    private fun structureKey(p: Project): String = buildString {
        append(p.canvasW).append('x').append(p.canvasH).append('@').append(p.fps).append(pro).append('|')
        for (t in p.tracks) {
            append(t.id).append(t.kind).append(t.visible).append(t.muted).append('[')
            for (c in t.clips) {
                append(c.id)
                if (t.kind != TrackKind.TEXT) {
                    append(c.uri.hashCode()).append(if (c.proxyState == ProxyState.READY) c.proxyPath else "")
                    append(c.trimStartUs).append(',').append(c.trimEndUs).append(',').append(c.speed).append(',').append(c.volume)
                    append(',').append(c.brightness).append(',').append(c.contrast).append(',').append(c.saturation)
                    if (t.kind != TrackKind.MAIN) append(',').append(c.startUs)
                }
                append(';')
            }
            append(']')
        }
    }

    private fun refresh() {
        val key = structureKey(project)
        if (key != lastKey) {
            rebuildJob?.cancel()
            rebuildJob = scope.launch { delay(180); rebuildNow() }
        } else if (!playing) {
            redrawJob?.cancel()
            redrawJob = scope.launch { delay(50); player.seekTo(positionUs / 1000) }
        }
    }

    private fun rebuildNow() {
        if (released) return
        lastKey = structureKey(project)
        if (project.isEmpty) { player.stop(); return }
        val (w, h) = previewSize(project)
        val wasPlaying = player.isPlaying
        val pos = positionUs.coerceIn(0L, max(0L, project.durationUs - 1000))
        runCatching {
            val comp = factory.build(ref, w, h, forExport = false, watermark = !pro)
            player.setComposition(comp, pos / 1000)
            player.prepare()
            if (wasPlaying) player.play()
        }.onFailure { message = "Couldn't build preview: ${it.message}" }
    }

    fun togglePlay() {
        if (project.isEmpty) return
        if (player.isPlaying) { player.pause(); return }
        if (positionUs >= project.durationUs - 50_000) seek(0)
        player.play()
    }

    fun pause() { if (player.isPlaying) player.pause() }

    fun seek(us: Long) {
        val t = us.coerceIn(0L, project.durationUs)
        positionUs = t
        player.seekTo(t / 1000)
    }

    fun setScrubbing(on: Boolean) {
        runCatching { player.setScrubbingModeEnabled(on) }
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                positionUs = player.currentPosition * 1000
                delay(16)
            }
        }
    }

    fun release() {
        released = true
        tickJob?.cancel(); rebuildJob?.cancel(); redrawJob?.cancel()
        saveNow()
        player.release()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(600)
            val p = project
            withContext(Dispatchers.IO) { store.save(p) }
        }
    }

    fun saveNow() {
        saveJob?.cancel()
        store.save(project)
    }

    // ---- importing ----

    fun import(uris: List<Uri>, target: Target) {
        if (uris.isEmpty()) return
        scope.launch {
            val clips = withContext(Dispatchers.IO) {
                uris.mapNotNull { u -> MediaProbe.persist(context, u); MediaProbe.probe(context, u) }
            }
            if (clips.isEmpty()) { message = "Couldn't read that file."; return@launch }
            val bad = clips.filter { !DeviceCaps.canDecode(it) }
            if (bad.isNotEmpty()) message = "This phone can't decode 8K video, so ${bad.size} clip(s) were skipped."
            val ok = clips.filter { DeviceCaps.canDecode(it) }.map {
                if (DeviceCaps.needsProxy(it)) it.copy(proxyState = ProxyState.PENDING) else it
            }
            if (ok.isEmpty()) return@launch
            var p = project
            when (target) {
                Target.MAIN -> {
                    p = p.mapTrack(p.main.id) { it.copy(clips = it.clips + ok.filter { c -> c.isVisual }) }
                    ok.filter { it.kind == ClipKind.AUDIO }.forEach { a -> p = p.placeOnFreeTrack(TrackKind.AUDIO, a, positionUs) }
                }
                Target.OVERLAY -> {
                    for (c in ok.filter { it.isVisual }) {
                        val placed = c.copy(scale = if (p.main.clips.isEmpty()) 1f else 0.5f)
                        val next = p.placeOnFreeTrack(TrackKind.VIDEO, placed, positionUs)
                        if (!pro && next.overlayTrackCount > Tier.FREE_OVERLAY_LAYERS) {
                            proPrompt = "Free includes ${Tier.FREE_OVERLAY_LAYERS} overlay layers. Pro gives you unlimited layers."
                            break
                        }
                        p = next
                    }
                }
                Target.AUDIO -> ok.filter { it.kind == ClipKind.AUDIO || it.hasAudio }.forEach { a ->
                    val audio = a.copy(kind = ClipKind.AUDIO, proxyState = ProxyState.NONE)
                    p = p.placeOnFreeTrack(TrackKind.AUDIO, audio, positionUs)
                }
            }
            commit(p)
            if (selectedId == null) {
                val added = p.tracks.flatMap { it.clips }.lastOrNull { c -> ok.any { it.id == c.id } }
                if (target == Target.OVERLAY) selectedId = added?.id
            }
            ok.filter { it.proxyState == ProxyState.PENDING }.forEach { requestProxy(it) }
        }
    }

    private fun requestProxy(clip: Clip) {
        proxies.enqueue(clip) { path ->
            if (released) return@enqueue
            val state = if (path != null) ProxyState.READY else ProxyState.FAILED
            var p = project
            p.tracks.flatMap { it.clips }.filter { it.uri == clip.uri }.forEach { c ->
                p = p.mapClip(c.id) { it.copy(proxyPath = path, proxyState = state) }
            }
            apply(p)
            if (path == null) message = "Couldn't make a preview copy of ${clip.name}. Editing it may be slow."
        }
    }

    // ---- clip edits ----

    fun select(id: String?) {
        if (id != null) {
            val (t, _) = project.find(id) ?: return
            if (t.locked) { message = "${t.name} is locked. Unlock it in Layers."; return }
        }
        selectedId = if (selectedId == id) null else id
    }

    fun split() {
        val id = selectedId ?: return
        val r = Edits.split(project, id, positionUs) ?: run { message = "Move the playhead inside the clip to split."; return }
        commit(r.first)
        selectedId = r.second
    }

    fun delete() {
        val id = selectedId ?: return
        commit(project.removeClip(id))
        selectedId = null
    }

    fun duplicate() {
        val id = selectedId ?: return
        val (t, _) = project.find(id) ?: return
        val r = Edits.duplicate(project, id) ?: return
        if (!pro && t.kind == TrackKind.VIDEO && r.first.overlayTrackCount > Tier.FREE_OVERLAY_LAYERS) {
            proPrompt = "Free includes ${Tier.FREE_OVERLAY_LAYERS} overlay layers. Pro gives you unlimited layers."
            return
        }
        commit(r.first)
        selectedId = r.second
    }

    fun reorderMain(dir: Int) {
        val id = selectedId ?: return
        commit(Edits.reorderMain(project, id, dir))
    }

    fun speedRange(): ClosedFloatingPointRange<Float> = if (pro) Tier.PRO_SPEED else Tier.FREE_SPEED

    fun setSpeed(speed: Float) {
        val id = selectedId ?: return
        commit(Edits.setSpeed(project, id, speed), undoable = false)
    }

    fun setClip(undoable: Boolean = false, f: (Clip) -> Clip) {
        val id = selectedId ?: return
        commit(project.mapClip(id, f), undoable)
    }

    fun setProp(p: Prop, v: Float) {
        val local = selectedLocalUs
        setClip { Edits.setProp(it, p, v, local) }
    }

    fun valueOf(p: Prop): Float = selectedClip?.valueAt(p, selectedLocalUs) ?: 0f

    fun keyAtPlayhead(): Boolean = selectedClip?.let { Edits.hasKeyAt(it, selectedLocalUs) } ?: false

    fun toggleKeyframe() {
        if (!pro) { proPrompt = "Keyframe animation is a Pro feature."; return }
        val local = selectedLocalUs
        setClip(undoable = true) { if (Edits.hasKeyAt(it, local)) Edits.removeKeyframesAt(it, local) else Edits.addKeyframeAll(it, local) }
    }

    fun jumpKeyframe(dir: Int) {
        val c = selectedClip ?: return
        val times = c.keyframeTimes()
        val local = selectedLocalUs
        val t = if (dir > 0) times.firstOrNull { it > local + 40_000 } else times.lastOrNull { it < local - 40_000 }
        if (t != null) seek(selectedStartUs + t)
    }

    fun resetTransform() {
        setClip(undoable = true) { it.copy(x = 0f, y = 0f, scale = 1f, rotation = 0f, opacity = 1f, keyframes = emptyMap()) }
    }

    /** Pinch / drag on the preview. */
    fun gestureTransform(dxN: Float, dyN: Float, zoom: Float, rotDeg: Float) {
        val c = selectedClip ?: return
        if (!c.isVisual) return
        val now = System.currentTimeMillis()
        if (now - lastGestureMs > 400) pushUndo()
        lastGestureMs = now
        val local = selectedLocalUs
        var u = c
        u = Edits.setProp(u, Prop.X, (c.valueAt(Prop.X, local) + dxN).coerceIn(-2f, 2f), local)
        u = Edits.setProp(u, Prop.Y, (c.valueAt(Prop.Y, local) + dyN).coerceIn(-2f, 2f), local)
        if (zoom != 1f) u = Edits.setProp(u, Prop.SCALE, (c.valueAt(Prop.SCALE, local) * zoom).coerceIn(0.05f, 5f), local)
        if (rotDeg != 0f) u = Edits.setProp(u, Prop.ROTATION, c.valueAt(Prop.ROTATION, local) + rotDeg, local)
        commit(project.mapClip(c.id) { u }, undoable = false)
    }

    fun trimStart(id: String, deltaUs: Long) = commit(Edits.trimStart(project, id, deltaUs), undoable = false)
    fun trimEnd(id: String, deltaUs: Long) = commit(Edits.trimEnd(project, id, deltaUs), undoable = false)
    fun moveBy(id: String, deltaUs: Long) {
        val start = project.startOf(id)
        commit(Edits.moveTo(project, id, start + deltaUs), undoable = false)
    }

    // ---- text ----

    fun addText(text: String, color: Int, size: Float, bold: Boolean) {
        val clip = Clip(
            kind = ClipKind.TEXT, name = text.take(24), text = text, textColor = color, textSize = size, textBold = bold,
            sourceDurationUs = MediaProbe.STILL_MAX_US, trimStartUs = 0, trimEndUs = 3_000_000,
        )
        val p = project.placeOnFreeTrack(TrackKind.TEXT, clip, positionUs)
        commit(p)
        selectedId = clip.id
    }

    // ---- tracks ----

    fun updateTrack(id: String, f: (Track) -> Track) = commit(project.mapTrack(id, f))

    fun moveTrack(id: String, dir: Int) {
        val list = project.tracks.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val kindGroup = { t: Track -> when (t.kind) { TrackKind.TEXT -> 0; TrackKind.VIDEO, TrackKind.MAIN -> 1; TrackKind.AUDIO -> 2 } }
        val j = i + dir
        if (j < 0 || j >= list.size || kindGroup(list[j]) != kindGroup(list[i])) return
        val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        commit(project.copy(tracks = list))
    }

    fun deleteTrack(id: String) {
        val t = project.tracks.firstOrNull { it.id == id } ?: return
        if (t.kind == TrackKind.MAIN) {
            commit(project.mapTrack(id) { it.copy(clips = emptyList()) })
        } else {
            commit(project.copy(tracks = project.tracks.filterNot { it.id == id }))
        }
    }

    fun setCanvas(w: Int, h: Int, fps: Int) = commit(project.copy(canvasW = w, canvasH = h, fps = fps))

    fun rename(name: String) = commit(project.copy(name = name.ifBlank { project.name }))
}
