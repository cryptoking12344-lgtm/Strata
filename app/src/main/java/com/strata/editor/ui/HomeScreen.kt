package com.strata.editor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.strata.editor.data.DeviceCaps
import com.strata.editor.model.Project
import java.text.DateFormat
import java.util.Date

data class NewProjectSpec(val w: Int, val h: Int, val fps: Int)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    projects: List<Project>,
    pro: Boolean,
    onTogglePro: (Boolean) -> Unit,
    onNew: (NewProjectSpec) -> Unit,
    onOpen: (Project) -> Unit,
    onDelete: (Project) -> Unit,
    onDuplicate: (Project) -> Unit,
    onOpenPro: () -> Unit,
) {
    val c = LocalStrata.current
    val caps = remember { DeviceCaps.get() }
    var newDialog by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Project?>(null) }
    var showCaps by remember { mutableStateOf(false) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().background(c.bg),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Logo()
                Spacer(Modifier.width(10.dp))
                Text("Strata", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                ProSwitch(pro, onTogglePro)
            }
        }
        item(span = { GridItemSpan(2) }) {
            Column(
                Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(20.dp)).background(c.accent)
                    .clickable { newDialog = true }.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(c.onAccent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, null, tint = c.accent)
                }
                Column {
                    Text("New project", color = c.onAccent, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Import video, photos or audio", color = c.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item(span = { GridItemSpan(2) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTile(Icons.Filled.Star, if (pro) "Pro is on" else "Pro features", Modifier.weight(1f), onOpenPro)
                QuickTile(Icons.Filled.Memory, "Device check", Modifier.weight(1f)) { showCaps = true }
            }
        }
        item(span = { GridItemSpan(2) }) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
                    .border(1.dp, c.border, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (caps.decode8k) Color(0xFF6BD48A) else Color(0xFFE8B94A)))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        if (caps.decode8k) "8K editing supported on this phone" else "4K editing supported on this phone",
                        color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Export up to 4K 60fps · ${caps.maxDecoders} video decoders",
                        color = c.textDim, fontSize = 12.sp,
                    )
                }
            }
        }
        item(span = { GridItemSpan(2) }) {
            Text("Projects", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
        }
        if (projects.isEmpty()) {
            item(span = { GridItemSpan(2) }) {
                Text("No projects yet. Tap New project to start.", color = c.textDim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 24.dp))
            }
        }
        items(projects, key = { it.id }) { p ->
            Column(
                Modifier.combinedClickable(onClick = { onOpen(p) }, onLongClick = { menuFor = p }),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(12.dp)).background(ClipColors.main)) {
                    val first = p.tracks.flatMap { it.clips }.firstOrNull { it.isVisual && it.kind != com.strata.editor.model.ClipKind.TEXT }
                    if (first != null) Thumb(first, first.trimStartUs, Modifier.fillMaxSize())
                    Box(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                        Badge(if (p.shortSide >= 2160) "4K" else "${p.shortSide}p", filled = p.shortSide >= 2160)
                    }
                    Text(
                        formatTime(p.durationUs), color = c.text, style = Mono.style.copy(fontSize = 10.sp),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(4.dp))
                            .background(c.bg).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Text(p.name, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    "${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.updatedAt))} · ${p.layerCount} layer${if (p.layerCount == 1) "" else "s"}",
                    color = c.textDim, fontSize = 11.sp,
                )
            }
        }
    }

    if (newDialog) NewProjectDialog(onDismiss = { newDialog = false }) { spec -> newDialog = false; onNew(spec) }

    menuFor?.let { p ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            containerColor = c.surface,
            title = { Text(p.name, color = c.text) },
            text = { Text("Duplicate or delete this project?", color = c.textDim) },
            confirmButton = { TextButton(onClick = { onDuplicate(p); menuFor = null }) { Text("Duplicate", color = c.accent) } },
            dismissButton = { TextButton(onClick = { onDelete(p); menuFor = null }) { Text("Delete", color = Color(0xFFFF6B6B)) } },
        )
    }

    if (showCaps) {
        AlertDialog(
            onDismissRequest = { showCaps = false },
            containerColor = c.surface,
            title = { Text("This phone", color = c.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CapLine("8K decoding", caps.decode8k)
                    CapLine("4K decoding", caps.decode4k)
                    CapLine("HEVC encoder", caps.hevcEncoder)
                    CapLine("AV1 encoder", caps.av1Encoder)
                    Text("Up to ${caps.maxDecoders} video decoders at once (fewer at 4K/8K). Strata makes small preview copies of 4K/8K clips so many layers stay smooth.", color = c.textDim, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { showCaps = false }) { Text("OK", color = c.accent) } },
        )
    }
}

@Composable
private fun CapLine(label: String, ok: Boolean) {
    val c = LocalStrata.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(if (ok) "Yes" else "No", color = if (ok) Color(0xFF6BD48A) else c.textDim, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun QuickTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalStrata.current
    Column(
        modifier.height(72.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Logo() {
    val c = LocalStrata.current
    Column(Modifier.width(30.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
        Box(Modifier.padding(start = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(2.dp)).background(c.text))
        Box(Modifier.padding(end = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF5A606B)))
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (NewProjectSpec) -> Unit) {
    val c = LocalStrata.current
    val aspects = listOf("16:9" to (16 to 9), "9:16" to (9 to 16), "1:1" to (1 to 1), "4:5" to (4 to 5))
    var a by remember { mutableIntStateOf(1) }
    var q by remember { mutableIntStateOf(0) }
    var f by remember { mutableIntStateOf(2) }
    val fpsList = listOf(24, 25, 30, 60)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("New project", color = c.text, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Aspect ratio", color = c.textDim, fontSize = 12.sp)
                Segmented(aspects.map { it.first }, a) { a = it }
                Text("Quality", color = c.textDim, fontSize = 12.sp)
                Segmented(listOf("1080p", "4K"), q) { q = it }
                Text("Frame rate", color = c.textDim, fontSize = 12.sp)
                Segmented(fpsList.map { "$it" }, f) { f = it }
                Text("You'll pick clips next. 8K clips import fine; exports go up to 4K 60fps.", color = c.textDim, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (aw, ah) = aspects[a].second
                val (w, h) = canvasFor(aw, ah, if (q == 1) 2160 else 1080)
                onCreate(NewProjectSpec(w, h, fpsList[f]))
            }) { Text("Pick clips", color = c.accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.textDim) } },
    )
}
