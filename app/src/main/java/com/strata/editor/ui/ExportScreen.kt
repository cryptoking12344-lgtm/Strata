package com.strata.editor.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.strata.editor.data.DeviceCaps
import com.strata.editor.data.Tier
import com.strata.editor.engine.Codec
import com.strata.editor.engine.ExportMath
import com.strata.editor.engine.ExportSettings
import com.strata.editor.engine.Exporter
import com.strata.editor.model.Project
import kotlin.math.roundToInt

private val SHORT_SIDES = listOf(720, 1080, 1440, 2160)
private val SHORT_LABELS = listOf("720p", "1080p", "2K", "4K")
private val FPS = listOf(24, 25, 30, 60)

@Composable
fun ExportScreen(project: Project, pro: Boolean, onBack: () -> Unit, onTogglePro: (Boolean) -> Unit, onOpenPro: () -> Unit) {
    val c = LocalStrata.current
    val context = LocalContext.current
    val view = LocalView.current
    val exporter = remember { Exporter(context) }

    val maxShort = if (pro) Tier.PRO_MAX_SHORT_SIDE else Tier.FREE_MAX_SHORT_SIDE
    val maxFps = if (pro) Tier.PRO_MAX_FPS else Tier.FREE_MAX_FPS
    var shortIdx by remember { mutableIntStateOf(SHORT_SIDES.indexOfLast { it <= minOf(project.shortSide, maxShort) }.coerceAtLeast(0)) }
    var fpsIdx by remember { mutableIntStateOf(FPS.indexOfLast { it <= minOf(project.fps, maxFps) }.coerceAtLeast(0)) }
    var codec by remember { mutableStateOf(Codec.H264) }
    var bitrateMbps by remember { mutableFloatStateOf(0f) } // 0 = auto
    var progress by remember { mutableStateOf<Int?>(null) }
    var result by remember { mutableStateOf<Pair<Uri?, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<String?>(null) }

    // Keep choices inside the tier when Pro is switched off.
    if (SHORT_SIDES[shortIdx] > maxShort) shortIdx = SHORT_SIDES.indexOf(maxShort)
    if (FPS[fpsIdx] > maxFps) fpsIdx = FPS.indexOf(maxFps)
    if (!pro && codec != Codec.H264) codec = Codec.H264

    val caps = remember { DeviceCaps.get() }
    val fps = FPS[fpsIdx]
    val (w, h) = ExportMath.outputSize(project, SHORT_SIDES[shortIdx])
    val autoRate = ExportMath.autoBitrate(w, h, fps, codec)
    val rate = if (pro && bitrateMbps > 0f) (bitrateMbps * 1_000_000).toInt() else autoRate
    val bytes = ExportMath.estimateBytes(rate, project.durationUs)

    val sizeDisabled = SHORT_SIDES.indices.filter { i ->
        val (ow, oh) = ExportMath.outputSize(project, SHORT_SIDES[i])
        !DeviceCaps.canEncode(codec.mime, ow, oh, fps)
    }.toSet()
    val fpsDisabled = FPS.indices.filter { i -> !DeviceCaps.canEncode(codec.mime, w, h, FPS[i]) }.toSet()
    val codecDisabled = buildSet {
        if (!caps.hevcEncoder) add(1)
        if (!caps.av1Encoder) add(2)
    }

    DisposableEffect(Unit) { onDispose { exporter.cancel(); view.keepScreenOn = false } }
    BackHandler(enabled = progress != null) { /* stay while rendering; use Cancel */ }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = progress == null) { Icon(Icons.Filled.Close, "Close", tint = c.text) }
                Text("Export", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                if (c.pro) Badge("PRO")
                Spacer(Modifier.width(12.dp))
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Section("Resolution") {
                    Segmented(SHORT_LABELS, shortIdx, disabled = sizeDisabled, locked = if (pro) emptySet() else setOf(2, 3)) { i ->
                        if (SHORT_SIDES[i] > maxShort) prompt = "2K and 4K export are Pro features. Free exports up to 1080p."
                        else shortIdx = i
                    }
                    Text("$w × $h${if (project.shortSide > SHORT_SIDES[shortIdx]) " · downscaled from the ${project.canvasW}×${project.canvasH} project" else ""}", color = c.textDim, style = Mono.style.copy(fontSize = 11.sp))
                }
                Section("Frame rate") {
                    Segmented(FPS.map { "$it" }, fpsIdx, disabled = fpsDisabled, locked = if (pro) emptySet() else setOf(3)) { i ->
                        if (FPS[i] > maxFps) prompt = "60fps export is a Pro feature." else fpsIdx = i
                    }
                    Text("Max export: 4K at 60 fps", color = c.textDim, fontSize = 11.sp)
                }
                Section("Codec") {
                    Segmented(Codec.entries.map { it.label }, codec.ordinal, disabled = codecDisabled, locked = if (pro) emptySet() else setOf(1, 2)) { i ->
                        if (!pro && i > 0) prompt = "HEVC and AV1 give smaller files at the same quality. They're Pro features."
                        else codec = Codec.entries[i]
                    }
                    if (codecDisabled.isNotEmpty()) Text("Greyed out = this phone has no hardware encoder for it.", color = c.textDim, fontSize = 11.sp)
                }
                if (pro) {
                    SliderRow(
                        "Bitrate", if (bitrateMbps == 0f) autoRate / 1_000_000f else bitrateMbps, 2f..100f,
                        display = { "${it.roundToInt()} Mbps" }, onChange = { bitrateMbps = it },
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bitrate: auto (${autoRate / 1_000_000} Mbps)", color = c.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Chip("Custom", selected = false, locked = true) { prompt = "Custom bitrate is a Pro feature." }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoTile("Estimated size", humanBytes(bytes), Modifier.weight(1f))
                    InfoTile("Watermark", if (pro) "None" else "Strata", Modifier.weight(1f))
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(c.accent)
                        .clickable(enabled = progress == null) {
                            error = null
                            progress = 0
                            view.keepScreenOn = true
                            exporter.start(
                                project,
                                ExportSettings(SHORT_SIDES[shortIdx], fps, codec, if (pro && bitrateMbps > 0f) rate else 0, watermark = !pro),
                                onProgress = { progress = it },
                                onDone = { uri, name -> progress = null; view.keepScreenOn = false; result = uri to name },
                                onError = { msg -> progress = null; view.keepScreenOn = false; error = msg },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Export ${SHORT_LABELS[shortIdx]} video", color = c.onAccent, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                }
                Text(
                    "Renders from your full-quality originals, not the preview copies.",
                    color = c.textDim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally),
                )
            }
        }

        progress?.let { p ->
            Overlay {
                Text("Exporting… $p%", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(progress = { p / 100f }, color = c.accent, trackColor = c.border, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text("Keep Strata open until it finishes.", color = c.textDim, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                OutlineButton("Cancel") { exporter.cancel(); progress = null; view.keepScreenOn = false }
            }
        }
        result?.let { (uri, name) ->
            Overlay {
                Text(if (uri != null) "Saved" else "Rendered, but couldn't save", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Spacer(Modifier.height(6.dp))
                Text(if (uri != null) "Movies/Strata/$name" else "Check storage space and try again.", color = c.textDim, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (uri != null) OutlineButton("Share") {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Share video"))
                    }
                    OutlineButton("Done") { result = null; onBack() }
                }
            }
        }
        error?.let { msg ->
            Overlay {
                Text("Export failed", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(msg, color = c.textDim, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                OutlineButton("OK") { error = null }
            }
        }
    }

    prompt?.let { why ->
        ProPromptDialog(why, onDismiss = { prompt = null }, onEnable = { onTogglePro(true); prompt = null }, onLearn = { prompt = null; onOpenPro() })
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = LocalStrata.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
        content()
    }
}

@Composable
private fun InfoTile(label: String, value: String, modifier: Modifier) {
    val c = LocalStrata.current
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, color = c.textDim, fontSize = 11.sp)
        Text(value, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
    }
}

@Composable
private fun Overlay(content: @Composable () -> Unit) {
    val c = LocalStrata.current
    Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.92f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(28.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
fun OutlineButton(label: String, onClick: () -> Unit) {
    val c = LocalStrata.current
    Box(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).border(1.dp, c.accent, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = c.accent, fontWeight = FontWeight.Bold) }
}

fun humanBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.2f GB".format(b / 1e9)
    b >= 1_000_000 -> "${(b / 1e6).roundToInt()} MB"
    else -> "${(b / 1e3).roundToInt()} KB"
}
