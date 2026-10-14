package com.myedits.app.ui.common

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myedits.app.ui.theme.EditsColors
import java.util.Locale

fun formatTime(ms: Long, tenths: Boolean = true): String {
    val total = ms.coerceAtLeast(0L)
    val m = total / 60000
    val s = (total / 1000) % 60
    val t = (total / 100) % 10
    return if (tenths) String.format(Locale.US, "%d:%02d.%d", m, s, t) else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0L)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** Click without a ripple-less dead zone: a consistent pressed look and a minimum 48dp target. */
@Composable
fun ToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    tint: Color = Color.White,
    tag: String = "tool_$label",
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .testTag(tag)
            .defaultMinSize(minWidth = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
            .alpha(if (enabled) 1f else 0.35f)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (selected) EditsColors.Blue else EditsColors.Surface2),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = label, tint = if (selected) Color.White else tint, modifier = Modifier.size(22.dp)) }
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) Color.White else EditsColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tag: String = "btn_$text") {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.testTag(tag).height(44.dp),
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(containerColor = EditsColors.Blue, contentColor = Color.White, disabledContainerColor = EditsColors.Surface3, disabledContentColor = EditsColors.TextDim),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tag: String = "btn_$text") {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.testTag(tag).height(44.dp),
        shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, EditsColors.Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
    ) { Text(text, fontWeight = FontWeight.Medium) }
}

/** A labelled slider with a live value read-out. */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    valueText: (Float) -> String,
    modifier: Modifier = Modifier,
    onFinished: (() -> Unit)? = null,
    tag: String = "slider_$label",
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            Text(valueText(value), style = MaterialTheme.typography.bodyMedium, color = EditsColors.TextDim, modifier = Modifier.testTag("$tag-value"))
        }
        Slider(
            value = value.coerceIn(range), onValueChange = onChange, valueRange = range,
            onValueChangeFinished = onFinished,
            modifier = Modifier.testTag(tag),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = EditsColors.Blue, inactiveTrackColor = EditsColors.Surface3),
        )
    }
}

/** Header used by every tool panel: title with cancel/confirm affordances. */
@Composable
fun PanelHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable (RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose, modifier = Modifier.testTag("panel_close")) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
        Text(title, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium, color = Color.White)
        if (trailing != null) trailing() else Spacer(Modifier.width(8.dp))
        IconButton(onClick = onClose, modifier = Modifier.testTag("panel_done")) { Icon(Icons.Filled.Check, "Done", tint = EditsColors.Blue) }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, tag: String = "chip_$text") {
    Box(
        modifier
            .testTag(tag)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Color.White else EditsColors.Surface2)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

@Composable
fun noRippleInteraction() = remember { MutableInteractionSource() }

@Composable
fun EmptyState(emoji: String, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(emoji, fontSize = 44.sp)
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = EditsColors.TextDim, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(8.dp)); action() }
    }
}
