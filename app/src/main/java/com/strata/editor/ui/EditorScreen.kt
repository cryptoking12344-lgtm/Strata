@file:Suppress("UnsafeOptInUsageError")

package com.strata.editor.ui

import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.strata.editor.data.ProjectStore
import com.strata.editor.engine.CompositionFactory
import com.strata.editor.engine.ProxyMaker
import com.strata.editor.model.ClipKind
import com.strata.editor.model.Project
import com.strata.editor.model.Prop
import com.strata.editor.model.TrackKind
import kotlinx.coroutines.delay

@Composable
fun EditorScreen(
    initial: Project,
    pendingImport: List<Uri>,
    onImportConsumed: () -> Unit,
    store: ProjectStore,
    proxies: ProxyMaker,
    pro: Boolean,
    onBack: () -> Unit,
    onExport: (Project) -> Unit,
    onOpenPro: () -> Unit,
    onTogglePro: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ed = remember(initial.id) { EditorController(context, initial, store, proxies, scope, pro) }
    DisposableEffect(ed) { onDispose { ed.release() } }
    LaunchedEffect(pro) { ed.updatePro(pro) }
    LaunchedEffect(initial.id) {
        if (pendingImport.isNotEmpty()) {
            ed.import(pendingImport, Target.MAIN)
            onImportConsumed()
        }
    }

    val c = LocalStrata.current
    var panel by remember { mutableStateOf(Panel.NONE) }
    var textEdit by remember { mutableStateOf<String?>(null) } // clip id, or "" for new
    var renaming by remember { mutableStateOf(false) }

    val pickMain = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { ed.import(it, Target.MAIN) }
    val pickOverlay = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { ed.import(it, Target.OVERLAY) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { ed.import(it, Target.AUDIO) }

    // Panels that need a clip close when the selection goes away.
    val sel = ed.selected
    LaunchedEffect(sel?.second?.id) {
        if (sel == null && panel in setOf(Panel.SPEED, Panel.VOLUME, Panel.TRANSFORM, Panel.ADJUST)) panel = Panel.NONE
    }
    LaunchedEffect(ed.message) { if (ed.message != null) { delay(4000); ed.message = null } }

    BackHandler {
        when {
            panel != Panel.NONE -> panel = Panel.NONE
            ed.selectedId != null -> ed.selectedId = null
            else -> { ed.pause(); ed.saveNow(); onBack() }
        }
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { ed.pause(); ed.saveNow(); onBack() }) { Icon(Icons.Filled.ArrowBack, "Back", tint = c.text) }
                Column(Modifier.weight(1f).clickable { renaming = true }) {
                    Text(ed.project.name, color = c.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val q = if (ed.project.shortSide >= 2160) "4K" else "${ed.project.shortSide}p"
                    Text("$q · ${ed.project.fps}fps · ${ed.project.canvasW}×${ed.project.canvasH}", color = c.textDim, style = Mono.style.copy(fontSize = 11.sp))
                }
                Box(
                    Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(c.accent)
                        .clickable(enabled = !ed.project.isEmpty) { ed.pause(); ed.saveNow(); onExport(ed.project) }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Export", color = c.onAccent, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp) }
                Spacer(Modifier.width(8.dp))
            }

            Preview(ed, Modifier.fillMaxWidth().height(250.dp))

            // Transport
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatTime(ed.positionUs, true, ed.project.fps)} / ${formatTime(ed.project.durationUs)}",
                    color = c.text, style = Mono.style, modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = { ed.undo() }, enabled = ed.undoCount > 0) { Icon(Icons.Filled.Undo, "Undo", tint = if (ed.undoCount > 0) c.text else c.textDim.copy(alpha = 0.4f)) }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(c.text).clickable { ed.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (ed.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (ed.playing) "Pause" else "Play", tint = c.bg) }
                IconButton(onClick = { ed.redo() }, enabled = ed.redoCount > 0) { Icon(Icons.Filled.Redo, "Redo", tint = if (ed.redoCount > 0) c.text else c.textDim.copy(alpha = 0.4f)) }
                IconButton(onClick = { if (ed.selectedClip?.isVisual == true) ed.toggleKeyframe() else ed.message = "Select a layer to add a keyframe." }) {
                    Box(Modifier.size(14.dp).graphicsLayer { rotationZ = 45f }.background(if (ed.keyAtPlayhead()) c.accent else Color.Transparent).border(2.dp, c.accent))
                }
            }

            Timeline(ed, Modifier.weight(1f).fillMaxWidth())

            when (panel) {
                Panel.SPEED -> SpeedPanel(ed) { panel = Panel.NONE }
                Panel.VOLUME -> VolumePanel(ed) { panel = Panel.NONE }
                Panel.TRANSFORM -> TransformPanel(ed) { panel = Panel.NONE }
                Panel.ADJUST -> AdjustPanel(ed) { panel = Panel.NONE }
                Panel.LAYERS -> LayersPanel(ed) { panel = Panel.NONE }
                Panel.CANVAS -> CanvasPanel(ed) { panel = Panel.NONE }
                Panel.NONE -> Toolbar(
                    ed,
                    onAddMain = { pickMain.launch(arrayOf("video/*", "image/*")) },
                    onAddOverlay = { pickOverlay.launch(arrayOf("video/*", "image/*")) },
                    onAddAudio = { pickAudio.launch(arrayOf("audio/*", "video/*")) },
                    onAddText = { textEdit = "" },
                    onEditText = { textEdit = ed.selectedId },
                    onPanel = { panel = it },
                )
            }
        }

        ed.message?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).padding(bottom = 76.dp),
                containerColor = c.surface2, contentColor = c.text,
            ) { Text(msg, fontSize = 13.sp) }
        }
    }

    textEdit?.let { id ->
        val existing = if (id.isNotEmpty()) ed.project.find(id)?.second else null
        TextDialog(
            initialText = existing?.text ?: "",
            initialColor = existing?.textColor ?: 0xFFFFFFFF.toInt(),
            initialSize = existing?.textSize ?: 0.08f,
            initialBold = existing?.textBold ?: true,
            onDismiss = { textEdit = null },
        ) { text, color, size, bold ->
            if (existing == null) ed.addText(text, color, size, bold)
            else ed.setClip(undoable = true) { it.copy(text = text, name = text.take(24), textColor = color, textSize = size, textBold = bold) }
            textEdit = null
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(ed.project.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = c.surface,
            title = { Text("Project name", color = c.text) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { ed.rename(name); renaming = false }) { Text("Save", color = c.accent) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel", color = c.textDim) } },
        )
    }

    ed.proPrompt?.let { why ->
        ProPromptDialog(why, onDismiss = { ed.proPrompt = null }, onEnable = { onTogglePro(true); ed.proPrompt = null }, onLearn = { ed.proPrompt = null; onOpenPro() })
    }
}

@Composable
fun ProPromptDialog(why: String, onDismiss: () -> Unit, onEnable: () -> Unit, onLearn: () -> Unit) {
    val c = LocalStrata.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Strata Pro", color = Color(0xFFE8B94A), fontWeight = FontWeight.ExtraBold) },
        text = { Text(why, color = c.text) },
        confirmButton = { TextButton(onClick = onEnable) { Text("Turn on Pro", color = Color(0xFFE8B94A), fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onLearn) { Text("See all Pro features", color = c.textDim) } },
    )
}

@Composable
private fun Preview(ed: EditorController, modifier: Modifier) {
    val c = LocalStrata.current
    val density = LocalDensity.current
    val p = ed.project
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val aspect = p.canvasW / p.canvasH.toFloat()
            val boxMod = Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = maxWidth / maxHeight > aspect)
            BoxWithConstraints(boxMod.background(Color(0xFF050505))) {
                val wPx = with(density) { maxWidth.toPx() }
                val hPx = with(density) { maxHeight.toPx() }
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            player = ed.player
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // Selection frame + gestures for the selected visual layer.
                val clip = ed.selectedClip
                if (clip != null && clip.isVisual) {
                    val local = ed.selectedLocalUs
                    val s = clip.valueAt(Prop.SCALE, local)
                    val (fw, fh) = if (clip.kind == ClipKind.TEXT) {
                        val hText = clip.textSize * p.canvasH * 1.3f
                        (hText * clip.text.length.coerceAtLeast(1) * 0.55f).toInt().coerceAtMost(p.canvasW) to hText.toInt()
                    } else CompositionFactory.fitSize(clip.width, clip.height, p.canvasW, p.canvasH)
                    val bw = fw / p.canvasW.toFloat() * wPx * s
                    val bh = fh / p.canvasH.toFloat() * hPx * s
                    val cx = (clip.valueAt(Prop.X, local) + 1f) / 2f * wPx
                    val cy = (1f - clip.valueAt(Prop.Y, local)) / 2f * hPx
                    val visibleNow = ed.positionUs >= ed.selectedStartUs && ed.positionUs < ed.selectedStartUs + clip.durationUs
                    if (visibleNow) {
                        Box(
                            Modifier
                                .offset(x = with(density) { (cx - bw / 2).toDp() }, y = with(density) { (cy - bh / 2).toDp() })
                                .size(with(density) { bw.toDp() }, with(density) { bh.toDp() })
                                .graphicsLayer { rotationZ = clip.valueAt(Prop.ROTATION, local) }
                                .border(1.5.dp, c.accent),
                        )
                    }
                    Box(
                        Modifier.fillMaxSize().pointerInput(clip.id, wPx, hPx) {
                            detectTransformGestures { _, pan, zoom, rotation ->
                                ed.gestureTransform(pan.x / wPx * 2f, -pan.y / hPx * 2f, zoom, rotation)
                            }
                        },
                    )
                }
            }
        }
        if (p.isEmpty) Text("Your video appears here", color = c.textDim, fontSize = 13.sp)
    }
}

@Composable
private fun Toolbar(
    ed: EditorController,
    onAddMain: () -> Unit,
    onAddOverlay: () -> Unit,
    onAddAudio: () -> Unit,
    onAddText: () -> Unit,
    onEditText: () -> Unit,
    onPanel: (Panel) -> Unit,
) {
    val c = LocalStrata.current
    Row(
        Modifier.fillMaxWidth().height(76.dp).background(c.bg).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val sel = ed.selected
        if (sel == null) {
            ToolButton(Icons.Filled.Add, "Add clip", highlight = true, onClick = onAddMain)
            val overlayLocked = !ed.pro && ed.project.overlayTrackCount >= 3
            ToolButton(Icons.Filled.Layers, "Overlay", locked = overlayLocked) {
                if (overlayLocked) ed.proPrompt = "Free includes 3 overlay layers. Pro gives you unlimited layers." else onAddOverlay()
            }
            ToolButton(Icons.Filled.TextFields, "Text", onClick = onAddText)
            ToolButton(Icons.Filled.MusicNote, "Audio", onClick = onAddAudio)
            ToolButton(Icons.Filled.Layers, "Layers") { onPanel(Panel.LAYERS) }
            ToolButton(Icons.Filled.AspectRatio, "Canvas") { onPanel(Panel.CANVAS) }
        } else {
            val (track, clip) = sel
            ToolButton(Icons.Filled.Check, "Done", highlight = true) { ed.selectedId = null }
            ToolButton(Icons.Filled.ContentCut, "Split") { ed.split() }
            if (clip.hasTimeSource) ToolButton(Icons.Filled.Speed, "Speed") { onPanel(Panel.SPEED) }
            if ((clip.kind == ClipKind.VIDEO && clip.hasAudio) || clip.kind == ClipKind.AUDIO) ToolButton(Icons.Filled.VolumeUp, "Volume") { onPanel(Panel.VOLUME) }
            if (clip.kind == ClipKind.TEXT) ToolButton(Icons.Filled.Edit, "Edit text", onClick = onEditText)
            if (clip.isVisual) ToolButton(Icons.Filled.OpenWith, "Transform") { onPanel(Panel.TRANSFORM) }
            if (clip.isVisual) ToolButton(Icons.Filled.Timeline, "Keyframe", locked = !ed.pro) { ed.toggleKeyframe() }
            if (clip.kind == ClipKind.VIDEO || clip.kind == ClipKind.IMAGE) ToolButton(Icons.Filled.Tune, "Adjust") { onPanel(Panel.ADJUST) }
            ToolButton(Icons.Filled.ContentCopy, "Duplicate") { ed.duplicate() }
            if (track.kind == TrackKind.MAIN) {
                ToolButton(Icons.Filled.ChevronLeft, "Move left") { ed.reorderMain(-1) }
                ToolButton(Icons.Filled.ChevronRight, "Move right") { ed.reorderMain(1) }
            }
            ToolButton(Icons.Filled.Delete, "Delete") { ed.delete() }
        }
    }
}
