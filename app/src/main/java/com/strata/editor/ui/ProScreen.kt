package com.strata.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Feature(val name: String, val free: String, val pro: String)

private val features = listOf(
    Feature("Overlay layers", "3", "Unlimited"),
    Feature("Export resolution", "Up to 1080p", "Up to 4K"),
    Feature("Export frame rate", "Up to 30fps", "Up to 60fps"),
    Feature("Codecs", "H.264", "H.264, HEVC, AV1"),
    Feature("Bitrate", "Auto", "Custom up to 100 Mbps"),
    Feature("Watermark", "Yes", "None"),
    Feature("Keyframe animation", "—", "Position, scale, rotation, opacity"),
    Feature("Speed", "0.5x–2x", "0.1x–10x"),
)

private val comingSoon = listOf(
    "Transitions and effects library",
    "Masks, chroma key and blend modes",
    "LUTs and color curves",
    "Speed ramps",
    "Auto captions and background removal",
)

@Composable
fun ProScreen(pro: Boolean, onToggle: (Boolean) -> Unit, onBack: () -> Unit) {
    val c = LocalStrata.current
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = c.text) }
            Text("Pro mode", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
                    .border(1.dp, c.accent, RoundedCornerShape(20.dp)).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Strata Pro", color = c.accent, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        if (pro) "Every tool unlocked. Gold theme on." else "Turn on to unlock everything and switch to the gold theme.",
                        color = c.textDim, fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = pro, onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedThumbColor = c.onAccent, checkedTrackColor = c.accent, uncheckedTrackColor = c.surface2),
                )
            }
            Text("Free during testing. Paid unlock comes later.", color = c.textDim, fontSize = 11.sp)

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("", modifier = Modifier.weight(1.3f))
                    Text("Free", color = c.textDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("Pro", color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                }
                features.forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.3f))
                        Text(f.free, color = c.textDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(f.pro, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                }
            }

            Text("In every version", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            listOf(
                "Multi-layer timeline with video, photo, text and audio layers",
                "Import 8K clips with automatic preview copies",
                "Trim, split, duplicate, reorder, undo and redo",
                "Transform layers by dragging and pinching the preview",
                "Brightness, contrast and saturation",
                "Volume per clip and per audio track",
            ).forEach { Bullet(it, done = true) }

            Text("Coming next to Pro", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            comingSoon.forEach { Bullet(it, done = false) }
        }
    }
}

@Composable
private fun Bullet(text: String, done: Boolean) {
    val c = LocalStrata.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(18.dp).clip(CircleShape).background(if (done) c.accent else c.surface2),
            contentAlignment = Alignment.Center,
        ) { if (done) Icon(Icons.Filled.Check, null, tint = c.onAccent, modifier = Modifier.size(12.dp)) }
        Spacer(Modifier.width(10.dp))
        Text(text, color = if (done) c.text else c.textDim, fontSize = 13.sp)
    }
}
