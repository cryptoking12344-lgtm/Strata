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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun formatTime(us: Long, withFrames: Boolean = false, fps: Int = 30): String {
    val totalMs = (us / 1000).coerceAtLeast(0)
    val m = totalMs / 60_000
    val s = (totalMs / 1000) % 60
    return if (withFrames) {
        val f = ((totalMs % 1000) * fps / 1000)
        "%02d:%02d:%02d".format(m, s, f)
    } else "%02d:%02d".format(m, s)
}

@Composable
fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, highlight: Boolean = false, locked: Boolean = false, onClick: () -> Unit) {
    val c = LocalStrata.current
    val tint = when {
        !enabled -> c.textDim.copy(alpha = 0.4f)
        highlight -> c.accent
        else -> c.text.copy(alpha = 0.85f)
    }
    Column(
        Modifier
            .width(64.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            if (locked) Icon(
                Icons.Filled.Lock, contentDescription = "Pro", tint = c.accent,
                modifier = Modifier.size(11.dp).align(Alignment.TopEnd).padding(start = 0.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onStart: () -> Unit = {},
    onChange: (Float) -> Unit,
    onEnd: () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalStrata.current
    val started = remember { booleanArrayOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = c.textDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(display(value), color = c.text, style = Mono.style)
            if (trailing != null) { Spacer(Modifier.width(6.dp)); trailing() }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = {
                if (!started[0]) { started[0] = true; onStart() }
                onChange(it)
            },
            onValueChangeFinished = { started[0] = false; onEnd() },
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.border),
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, disabled: Set<Int> = emptySet(), locked: Set<Int> = emptySet(), onSelect: (Int) -> Unit) {
    val c = LocalStrata.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            val dis = i in disabled
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) c.accent else c.surface)
                    .border(1.dp, if (sel) c.accent else c.border, RoundedCornerShape(10.dp))
                    .clickable(enabled = !dis) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        color = when { sel -> c.onAccent; dis -> c.textDim.copy(alpha = 0.4f); else -> c.text },
                        fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                    if (i in locked && !sel) {
                        Spacer(Modifier.width(3.dp))
                        Icon(Icons.Filled.Lock, null, tint = c.accent, modifier = Modifier.size(11.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ProSwitch(pro: Boolean, onToggle: (Boolean) -> Unit) {
    val c = LocalStrata.current
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, c.accent, RoundedCornerShape(20.dp))
            .clickable { onToggle(!pro) }
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("PRO", color = c.accent, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Switch(
            checked = pro,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent, checkedTrackColor = c.accent,
                uncheckedThumbColor = c.textDim, uncheckedTrackColor = c.surface2, uncheckedBorderColor = c.border,
            ),
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
fun Badge(text: String, filled: Boolean = true) {
    val c = LocalStrata.current
    Text(
        text,
        color = if (filled) c.onAccent else Color(0xFF0E0F12),
        fontSize = 10.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (filled) c.accent else Color(0xFFF2F3F5).copy(alpha = 0.9f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
