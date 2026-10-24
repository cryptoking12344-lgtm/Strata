package com.strata.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.strata.editor.model.Prop
import com.strata.editor.model.TrackKind
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

enum class Panel(val title: String) {
    NONE(""), SPEED("Speed"), VOLUME("Volume"), TRANSFORM("Transform"), ADJUST("Adjust"),
    LAYERS("Layers"), CANVAS("Canvas")
}

@Composable
fun PanelFrame(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val c = LocalStrata.current
    Column(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .heightIn(max = 300.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = c.textDim) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) { content() }
    }
}

@Composable
fun SpeedPanel(ed: EditorController, onClose: () -> Unit) {
    val clip = ed.selectedClip ?: return
    val range = ed.speedRange()
    val lo = ln(range.start) / ln(2f)
    val hi = ln(range.endInclusive) / ln(2f)
    PanelFrame("Speed", onClose) {
        SliderRow(
            "Speed", ln(clip.speed) / ln(2f), lo..hi,
            display = { "%.2fx".format(2f.pow(it)) },
            onStart = { ed.beginEdit() },
            onChange = { v -> ed.setSpeed(snapSpeed(2f.pow(v))) },
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.5f, 1f, 1.5f, 2f, 4f).forEach { s ->
                val allowed = s in range
                Chip("${s}x", selected = clip.speed == s, locked = !allowed) {
                    if (allowed) { ed.beginEdit(); ed.setSpeed(s) } else ed.proPrompt = "Speeds outside 0.5x–2x are a Pro feature (0.1x–10x)."
                }
            }
        }
        if (!ed.pro) ProHint("Pro unlocks 0.1x to 10x.")
    }
}

private fun snapSpeed(s: Float): Float {
    val r = (s * 20f).roundToInt() / 20f
    return if (kotlin.math.abs(r - 1f) < 0.04f) 1f else r
}

@Composable
fun VolumePanel(ed: EditorController, onClose: () -> Unit) {
    val clip = ed.selectedClip ?: return
    PanelFrame("Volume", onClose) {
        SliderRow(
            "Volume", clip.volume, 0f..2f, display = { "${(it * 100).roundToInt()}%" },
            onStart = { ed.beginEdit() }, onChange = { v -> ed.setClip { it.copy(volume = if (kotlin.math.abs(v - 1f) < 0.03f) 1f else v) } },
        )
    }
}

@Composable
fun TransformPanel(ed: EditorController, onClose: () -> Unit) {
    ed.selectedClip ?: return
    val c = LocalStrata.current
    PanelFrame("Transform", onClose) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { ed.jumpKeyframe(-1) }) { Icon(Icons.Filled.SkipPrevious, "Previous keyframe", tint = c.textDim) }
            val has = ed.keyAtPlayhead()
            Row(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (has) c.accent else c.surface2)
                    .clickable { ed.toggleKeyframe() },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(if (has) c.onAccent else c.accent, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (has) "Remove keyframe" else "Add keyframe",
                    color = if (has) c.onAccent else c.text, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                )
                if (!ed.pro) { Spacer(Modifier.width(6.dp)); Icon(Icons.Filled.Lock, null, tint = c.accent, modifier = Modifier.size(12.dp)) }
            }
            IconButton(onClick = { ed.jumpKeyframe(1) }) { Icon(Icons.Filled.SkipNext, "Next keyframe", tint = c.textDim) }
        }
        SliderRow("Position X", ed.valueOf(Prop.X), -1.5f..1.5f, { "%.2f".format(it) }, { ed.beginEdit() }, { ed.setProp(Prop.X, it) })
        SliderRow("Position Y", ed.valueOf(Prop.Y), -1.5f..1.5f, { "%.2f".format(it) }, { ed.beginEdit() }, { ed.setProp(Prop.Y, it) })
        SliderRow("Scale", ed.valueOf(Prop.SCALE), 0.05f..3f, { "${(it * 100).roundToInt()}%" }, { ed.beginEdit() }, { ed.setProp(Prop.SCALE, it) })
        SliderRow("Rotation", ed.valueOf(Prop.ROTATION), -180f..180f, { "${it.roundToInt()}°" }, { ed.beginEdit() }, { ed.setProp(Prop.ROTATION, it) })
        SliderRow("Opacity", ed.valueOf(Prop.OPACITY), 0f..1f, { "${(it * 100).roundToInt()}%" }, { ed.beginEdit() }, { ed.setProp(Prop.OPACITY, it) })
        TextButton(onClick = { ed.resetTransform() }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Reset transform", color = c.accent, fontWeight = FontWeight.Bold)
        }
        Text(
            "Tip: drag, pinch or twist the selected layer right on the preview.",
            color = c.textDim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
fun AdjustPanel(ed: EditorController, onClose: () -> Unit) {
    val clip = ed.selectedClip ?: return
    PanelFrame("Adjust", onClose) {
        SliderRow("Brightness", clip.brightness, -1f..1f, { "${(it * 100).roundToInt()}" }, { ed.beginEdit() }, { v -> ed.setClip { it.copy(brightness = v) } })
        SliderRow("Contrast", clip.contrast, -1f..1f, { "${(it * 100).roundToInt()}" }, { ed.beginEdit() }, { v -> ed.setClip { it.copy(contrast = v) } })
        SliderRow("Saturation", clip.saturation, -1f..1f, { "${(it * 100).roundToInt()}" }, { ed.beginEdit() }, { v -> ed.setClip { it.copy(saturation = v) } })
        TextButton(onClick = { ed.setClip(true) { it.copy(brightness = 0f, contrast = 0f, saturation = 0f) } }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Reset", color = LocalStrata.current.accent, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun LayersPanel(ed: EditorController, onClose: () -> Unit) {
    val c = LocalStrata.current
    val p = ed.project
    PanelFrame("Layers · ${p.tracks.size}", onClose) {
        p.tracks.forEach { t ->
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val swatch = when (t.kind) {
                    TrackKind.MAIN -> ClipColors.main; TrackKind.VIDEO -> ClipColors.video
                    TrackKind.TEXT -> ClipColors.text; TrackKind.AUDIO -> ClipColors.audio
                }
                Box(Modifier.size(32.dp, 24.dp).background(swatch, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("${t.clips.size} clip${if (t.clips.size == 1) "" else "s"}", color = c.textDim, fontSize = 11.sp)
                }
                IconButton(onClick = { ed.moveTrack(t.id, -1) }) { Icon(Icons.Filled.KeyboardArrowUp, "Move up", tint = c.textDim) }
                IconButton(onClick = { ed.moveTrack(t.id, 1) }) { Icon(Icons.Filled.KeyboardArrowDown, "Move down", tint = c.textDim) }
                if (t.kind == TrackKind.AUDIO) {
                    IconButton(onClick = { ed.updateTrack(t.id) { it.copy(muted = !it.muted) } }) {
                        Icon(if (t.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, "Mute", tint = c.text)
                    }
                } else {
                    IconButton(onClick = { ed.updateTrack(t.id) { it.copy(visible = !it.visible) } }) {
                        Icon(if (t.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, "Show or hide", tint = c.text)
                    }
                }
                IconButton(onClick = { ed.updateTrack(t.id) { it.copy(locked = !it.locked) } }) {
                    Icon(if (t.locked) Icons.Filled.Lock else Icons.Filled.LockOpen, "Lock", tint = if (t.locked) c.accent else c.textDim)
                }
                IconButton(onClick = { ed.deleteTrack(t.id) }) { Icon(Icons.Filled.Delete, "Delete layer", tint = c.textDim) }
            }
        }
        Text(
            if (ed.pro) "Pro: unlimited layers. Text always sits above video." else "Free: up to 3 overlay layers. Text always sits above video.",
            color = c.textDim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

private data class Aspect(val label: String, val w: Int, val h: Int)

private val aspects = listOf(Aspect("16:9", 16, 9), Aspect("9:16", 9, 16), Aspect("1:1", 1, 1), Aspect("4:5", 4, 5), Aspect("21:9", 21, 9))

/** Canvas size from aspect and short side (1080 or 2160), rounded to even numbers. */
fun canvasFor(aw: Int, ah: Int, short: Int): Pair<Int, Int> {
    fun even(v: Float) = (v / 2f).roundToInt() * 2
    return if (aw >= ah) even(short * aw / ah.toFloat()) to short else short to even(short * ah / aw.toFloat())
}

@Composable
fun CanvasPanel(ed: EditorController, onClose: () -> Unit) {
    val c = LocalStrata.current
    val p = ed.project
    val currentAspect = aspects.indexOfFirst { kotlin.math.abs(it.w / it.h.toFloat() - p.canvasW / p.canvasH.toFloat()) < 0.01f }
    val shortIdx = if (p.shortSide >= 2160) 1 else 0
    val fpsList = listOf(24, 25, 30, 60)
    PanelFrame("Canvas", onClose) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Aspect ratio", color = c.textDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Segmented(aspects.map { it.label }, currentAspect) { i ->
                val (w, h) = canvasFor(aspects[i].w, aspects[i].h, p.shortSide)
                ed.setCanvas(w, h, p.fps)
            }
            Text("Project quality", color = c.textDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Segmented(listOf("1080p", "4K"), shortIdx) { i ->
                val a = aspects.getOrNull(currentAspect) ?: aspects[0]
                val (w, h) = canvasFor(a.w, a.h, if (i == 1) 2160 else 1080)
                ed.setCanvas(w, h, p.fps)
            }
            Text("Frame rate", color = c.textDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Segmented(fpsList.map { "$it" }, fpsList.indexOf(p.fps).coerceAtLeast(0)) { i -> ed.setCanvas(p.canvasW, p.canvasH, fpsList[i]) }
            Text("${p.canvasW} × ${p.canvasH} · 8K clips are fine to import; export tops out at 4K 60fps.", color = c.textDim, fontSize = 11.sp)
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, locked: Boolean = false, onClick: () -> Unit) {
    val c = LocalStrata.current
    Row(
        Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))
            .background(if (selected) c.accent else c.surface2)
            .border(1.dp, if (selected) c.accent else c.border, RoundedCornerShape(17.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) c.onAccent else c.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (locked) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Lock, null, tint = c.accent, modifier = Modifier.size(11.dp)) }
    }
}

@Composable
fun ProHint(text: String) {
    val c = LocalStrata.current
    Text(text, color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

private val textColors = listOf(
    0xFFFFFFFF, 0xFF0E0F12, 0xFFC6F432, 0xFFE8B94A, 0xFFFF5A5F, 0xFF4DA3FF, 0xFFB57BFF, 0xFFFF8A3D,
).map { it.toInt() }

@Composable
fun TextDialog(
    initialText: String,
    initialColor: Int,
    initialSize: Float,
    initialBold: Boolean,
    onDismiss: () -> Unit,
    onDone: (String, Int, Float, Boolean) -> Unit,
) {
    val c = LocalStrata.current
    var text by remember { mutableStateOf(initialText) }
    var color by remember { mutableIntStateOf(initialColor) }
    var size by remember { mutableFloatStateOf(initialSize) }
    var bold by remember { mutableStateOf(initialBold) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Text", color = c.text, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Your text") }, minLines = 1, maxLines = 4)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    textColors.forEach { col ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Color(col))
                                .border(if (col == color) 3.dp else 1.dp, if (col == color) c.accent else c.border, CircleShape)
                                .clickable { color = col },
                        )
                    }
                }
                Column {
                    Text("Size ${(size * 100).roundToInt()}", color = c.textDim, fontSize = 12.sp)
                    androidx.compose.material3.Slider(value = size, onValueChange = { size = it }, valueRange = 0.03f..0.25f)
                }
                Chip("Bold", selected = bold) { bold = !bold }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onDone(text, color, size, bold) }) { Text("Done", color = c.accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.textDim) } },
    )
}

@Suppress("unused")
private fun Color.argbInt(): Int = toArgb()
