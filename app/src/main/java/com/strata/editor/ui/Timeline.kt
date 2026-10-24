package com.strata.editor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.strata.editor.engine.Thumbnails
import com.strata.editor.model.Clip
import com.strata.editor.model.ClipKind
import com.strata.editor.model.ProxyState
import com.strata.editor.model.Track
import com.strata.editor.model.TrackKind
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private val LABEL_W = 44.dp

private fun rowHeight(t: Track): Dp = when (t.kind) {
    TrackKind.MAIN -> 52.dp
    TrackKind.AUDIO -> 32.dp
    TrackKind.TEXT -> 30.dp
    TrackKind.VIDEO -> 38.dp
}

@Composable
fun Timeline(ed: EditorController, modifier: Modifier = Modifier) {
    val c = LocalStrata.current
    val density = LocalDensity.current
    var dpPerSec by remember { mutableFloatStateOf(70f) }
    val pxPerUs = with(density) { dpPerSec.dp.toPx() } / 1_000_000f
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val programmatic = remember { booleanArrayOf(false) }
    val project = ed.project

    BoxWithConstraints(modifier.background(Color(0xFF0B0C0F))) {
        val areaW = maxWidth - LABEL_W
        val half = areaW / 2
        val halfPx = with(density) { half.toPx() }
        val totalPx = project.durationUs * pxPerUs
        val contentW = with(density) { (halfPx * 2 + totalPx).toDp() } + 40.dp
        val pxNow by rememberUpdatedState(pxPerUs)

        // Follow the playhead (playback, seeks, zoom).
        LaunchedEffect(ed.positionUs, pxPerUs) {
            if (!hScroll.isScrollInProgress || programmatic[0]) {
                val target = (ed.positionUs * pxPerUs).roundToInt()
                if (abs(target - hScroll.value) > 1) {
                    programmatic[0] = true
                    hScroll.scrollTo(target)
                    programmatic[0] = false
                }
            }
        }
        // User scrubbing.
        LaunchedEffect(hScroll) {
            snapshotFlow { hScroll.isScrollInProgress && !programmatic[0] }.collect { userScrolling ->
                if (userScrolling) ed.pause()
                ed.setScrubbing(userScrolling)
            }
        }
        LaunchedEffect(hScroll) {
            snapshotFlow { hScroll.value }.collect { v ->
                if (hScroll.isScrollInProgress && !programmatic[0]) ed.seek((v / pxNow).toLong())
            }
        }

        Column(Modifier.fillMaxSize()) {
            // Ruler
            Box(Modifier.fillMaxWidth().height(22.dp)) {
                Ruler(Modifier.padding(start = LABEL_W).fillMaxSize(), hScroll.value.toFloat(), halfPx, pxPerUs, c.textDim)
                Row(Modifier.align(Alignment.CenterStart).width(LABEL_W), horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Filled.ZoomOut, "Zoom out", tint = c.textDim, modifier = Modifier.size(18.dp).clickable { dpPerSec = (dpPerSec / 1.5f).coerceAtLeast(8f) })
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.ZoomIn, "Zoom in", tint = c.textDim, modifier = Modifier.size(18.dp).clickable { dpPerSec = (dpPerSec * 1.5f).coerceAtMost(600f) })
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
                    Column(Modifier.width(LABEL_W).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        var v = 0; var t = 0; var a = 0
                        project.tracks.forEach { tr ->
                            val label = when (tr.kind) {
                                TrackKind.MAIN -> "MAIN"
                                TrackKind.VIDEO -> "V${++v}"
                                TrackKind.TEXT -> "T${++t}"
                                TrackKind.AUDIO -> "A${++a}"
                            }
                            Box(Modifier.height(rowHeight(tr)).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(label, color = if (tr.kind == TrackKind.MAIN) c.text else c.textDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    if (!tr.visible) Icon(Icons.Filled.VisibilityOff, "Hidden", tint = c.textDim, modifier = Modifier.size(11.dp))
                                    else if (tr.locked) Icon(Icons.Filled.Lock, "Locked", tint = c.textDim, modifier = Modifier.size(11.dp))
                                }
                            }
                        }
                    }
                    Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                        Column(
                            Modifier.width(contentW).padding(top = 4.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            project.tracks.forEach { tr ->
                                Box(
                                    Modifier.fillMaxWidth().height(rowHeight(tr))
                                        .pointerInput(tr.id) { detectTapGestures { ed.select(null) } },
                                ) {
                                    tr.placed().forEach { (clip, start) ->
                                        ClipBox(ed, tr, clip, start, halfPx, pxPerUs)
                                    }
                                }
                            }
                        }
                    }
                }
                // Playhead
                Box(
                    Modifier.padding(start = LABEL_W).offset(x = half - 1.dp).width(2.dp).fillMaxHeight()
                        .background(c.text),
                )
            }
        }
        Box(
            Modifier.padding(start = LABEL_W).offset(x = half - 6.dp, y = 0.dp).size(12.dp, 8.dp)
                .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)).background(c.text),
        )
        if (project.isEmpty) {
            Text(
                "Tap Add clip to start",
                color = c.textDim, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun Ruler(modifier: Modifier, scrollPx: Float, halfPx: Float, pxPerUs: Float, color: Color) {
    val density = LocalDensity.current
    val textPx = with(density) { 10.sp.toPx() }
    val paint = remember {
        android.graphics.Paint().apply { isAntiAlias = true; typeface = android.graphics.Typeface.MONOSPACE }
    }
    paint.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
    paint.textSize = textPx
    Canvas(modifier) {
        val pxPerSec = pxPerUs * 1_000_000f
        val step = listOf(1, 2, 5, 10, 15, 30, 60, 120, 300).first { it * pxPerSec >= with(density) { 64.dp.toPx() } }
        val startSec = ((scrollPx - halfPx) / pxPerSec).toInt().coerceAtLeast(0)
        var s = (startSec / step) * step
        while (true) {
            val x = halfPx + s * pxPerSec - scrollPx
            if (x > size.width) break
            if (x >= 0) {
                drawLine(color.copy(alpha = 0.5f), Offset(x, size.height - 6f), Offset(x, size.height), strokeWidth = 2f)
                drawContext.canvas.nativeCanvas.drawText(formatTime(s * 1_000_000L), x + 4f, textPx + 4f, paint)
            }
            s += step
        }
    }
}

@Composable
private fun ClipBox(ed: EditorController, track: Track, clip: Clip, start: Long, halfPx: Float, pxPerUs: Float) {
    val c = LocalStrata.current
    val density = LocalDensity.current
    val selected = ed.selectedId == clip.id
    val widthDp = with(density) { (clip.durationUs * pxPerUs).toDp() }.coerceAtLeast(6.dp)
    val color = when {
        clip.kind == ClipKind.TEXT -> ClipColors.text
        clip.kind == ClipKind.AUDIO -> ClipColors.audio
        clip.kind == ClipKind.IMAGE -> ClipColors.image
        track.kind == TrackKind.MAIN -> ClipColors.main
        else -> ClipColors.video
    }
    val pxRef by rememberUpdatedState(pxPerUs)
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .offset { IntOffset((halfPx + start * pxPerUs).roundToInt(), 0) }
            .width(widthDp)
            .fillMaxHeight()
            .clip(shape)
            .background(color)
            .then(if (selected) Modifier.border(2.dp, c.accent, shape) else Modifier)
            .pointerInput(clip.id) { detectTapGestures { ed.select(clip.id) } }
            .then(
                if (track.kind != TrackKind.MAIN && !track.locked) Modifier.pointerInput(clip.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { ed.selectedId = clip.id; ed.beginEdit() },
                    ) { change, drag ->
                        change.consume()
                        ed.moveBy(clip.id, (drag.x / pxRef).toLong())
                    }
                } else Modifier,
            ),
    ) {
        when (clip.kind) {
            ClipKind.VIDEO, ClipKind.IMAGE -> Filmstrip(clip, widthDp)
            ClipKind.AUDIO -> Wave(clip)
            ClipKind.TEXT -> {}
        }
        Row(Modifier.align(Alignment.TopStart).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (clip.kind == ClipKind.AUDIO) Icon(Icons.Filled.MusicNote, null, tint = Color.White, modifier = Modifier.size(11.dp))
            val label = when (clip.kind) {
                ClipKind.TEXT -> "T  ${clip.text}"
                else -> clip.name
            }
            Text(
                label + if (clip.speed != 1f) "  ${"%.1f".format(clip.speed)}x" else "",
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
            )
            if (clip.proxyState == ProxyState.PENDING) {
                Spacer(Modifier.width(4.dp)); Badge("PROXY…", filled = false)
            }
        }
        if (selected) {
            // Keyframe diamonds
            clip.keyframeTimes().forEach { t ->
                Box(
                    Modifier.align(Alignment.BottomStart)
                        .offset { IntOffset((t * pxPerUs).roundToInt() - 5, -4) }
                        .size(9.dp).rotate(45f).background(c.accent),
                )
            }
            if (!track.locked) {
                TrimHandle(Modifier.align(Alignment.CenterStart), c.accent, { ed.beginEdit() }) { dx -> ed.trimStart(clip.id, (dx / pxRef).toLong()) }
                TrimHandle(Modifier.align(Alignment.CenterEnd), c.accent, { ed.beginEdit() }) { dx -> ed.trimEnd(clip.id, (dx / pxRef).toLong()) }
            }
        }
    }
}

@Composable
private fun TrimHandle(modifier: Modifier, color: Color, onStart: () -> Unit, onDrag: (Float) -> Unit) {
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    Box(
        modifier
            .width(14.dp)
            .fillMaxHeight()
            .background(color)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { start() }) { change, dx ->
                    change.consume()
                    drag(dx)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(2.dp).height(14.dp).background(Color(0xFF0E0F12)))
    }
}

@Composable
private fun Filmstrip(clip: Clip, width: Dp) {
    val thumbW = 52.dp
    val count = ceil(width / thumbW).toInt().coerceIn(1, 40)
    Row(Modifier.fillMaxSize()) {
        for (i in 0 until count) {
            val local = (clip.durationUs * i / count)
            val src = if (clip.kind == ClipKind.VIDEO) clip.trimStartUs + (local * clip.speed).toLong() else 0L
            Thumb(clip, src, Modifier.width(thumbW).fillMaxHeight())
        }
    }
}

@Composable
fun Thumb(clip: Clip, timeUs: Long, modifier: Modifier) {
    val context = LocalContext.current
    val path = if (clip.proxyState == ProxyState.READY) clip.proxyPath else null
    val bmp by produceState<Bitmap?>(null, clip.uri, path, timeUs / 500_000) {
        value = Thumbnails.frame(context, clip.uri, path, timeUs, clip.kind == ClipKind.IMAGE)
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = modifier, alpha = 0.85f)
    } else {
        Box(modifier)
    }
}

@Composable
private fun Wave(clip: Clip) {
    val seed = clip.uri.hashCode()
    Canvas(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
        val bars = (size.width / 6f).toInt().coerceAtMost(600)
        for (i in 0 until bars) {
            val h = (((seed xor (i * 2654435761L).toInt()) ushr 3) % 100) / 100f
            val bh = size.height * (0.25f + 0.75f * abs(h)) * clip.volume.coerceIn(0.2f, 1f)
            val x = i * 6f + 3f
            drawLine(ClipColors.wave, Offset(x, (size.height - bh) / 2), Offset(x, (size.height + bh) / 2), strokeWidth = 2.5f)
        }
    }
}
