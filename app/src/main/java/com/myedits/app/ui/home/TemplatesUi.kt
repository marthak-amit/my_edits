package com.myedits.app.ui.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myedits.app.audio.SoundLibrary
import com.myedits.app.data.Template
import com.myedits.app.data.TemplateCategory
import com.myedits.app.data.Templates
import com.myedits.app.ui.common.Chip
import com.myedits.app.ui.common.PrimaryButton
import com.myedits.app.ui.common.formatDuration
import com.myedits.app.ui.common.tagsAsResourceIds
import com.myedits.app.ui.theme.EditsColors

private fun List<Long>.colors(): List<Color> = map { Color(it) }

/** Colourful, gently moving poster for a template: slot "frames" flick by under the title like a real preview would. */
@Composable
fun TemplatePoster(t: Template, modifier: Modifier = Modifier, compact: Boolean = false) {
    val inf = rememberInfiniteTransition(label = "poster")
    val drift by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    // which slot is "showing": loops over the template's slots at their real relative durations
    val loop by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(t.totalMs.toInt().coerceAtLeast(2000), easing = LinearEasing)), label = "loop")
    val cols = t.palette.colors()
    val acc = t.slots.runningFold(0L) { a, s -> a + s.ms }
    val now = loop * t.totalMs
    val slot = acc.indexOfLast { it <= now }.coerceIn(0, t.slotCount - 1)
    val brush = Brush.linearGradient(
        cols, start = Offset(300f * drift, 0f), end = Offset(600f - 300f * drift, 1200f),
    )
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(brush)) {
        // fake "frames" strip along the bottom: the current slot lights up
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            t.slots.forEachIndexed { i, s ->
                Box(Modifier.weight(s.ms.toFloat()).height(if (compact) 4.dp else 6.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == slot) 0.95f else 0.35f)))
            }
        }
        Column(Modifier.align(Alignment.Center).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t.emoji, fontSize = if (compact) 34.sp else 52.sp)
            t.slots[slot].caption?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Color.White, fontSize = if (compact) 11.sp else 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x66000000)).padding(horizontal = 7.dp, vertical = 3.dp)) {
            Text("${t.slotCount} clips · ${formatDuration(t.totalMs)}", color = Color.White, fontSize = if (compact) 9.sp else 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun TemplatesTab(onPick: (Template) -> Unit) {
    var category by remember { mutableStateOf<TemplateCategory?>(null) }
    val list = Templates.inCategory(category)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("All", category == null, { category = null }, tag = "tpl_cat_all")
            Templates.categories.forEach { c -> Chip(c.label, category == c, { category = c }, tag = "tpl_cat_${c.name}") }
        }
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize().testTag("inspiration_list"),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 110.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                Text("Pick a template, add your clips and we’ll cut them to the beat with transitions, motion, captions and music.", color = EditsColors.TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
            }
            items(list, key = { it.id }) { t ->
                Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable { onPick(t) }.testTag("template_${t.id}")) {
                    TemplatePoster(t, Modifier.fillMaxWidth().aspectRatio(0.72f))
                    Text(t.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
                    Text(t.tagline, color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
                }
            }
        }
    }
}

/** A horizontal strip of templates shown above the projects, like the “Templates” rail in Edits. */
@Composable
fun TemplateRail(onPick: (Template) -> Unit, onSeeAll: () -> Unit, edge: androidx.compose.ui.unit.Dp = 16.dp) {
    Column(Modifier.fillMaxWidth().testTag("template_rail")) {
        Row(Modifier.fillMaxWidth().padding(start = edge, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Templates", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onSeeAll, modifier = Modifier.testTag("template_rail_all")) { Text("See all", color = EditsColors.Blue) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = edge - 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Templates.all.take(8), key = { it.id }) { t ->
                Column(Modifier.width(104.dp).clip(RoundedCornerShape(12.dp)).clickable { onPick(t) }.testTag("rail_${t.id}")) {
                    TemplatePoster(t, Modifier.fillMaxWidth().aspectRatio(0.72f), compact = true)
                    Text(t.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateSheet(t: Template, onDismiss: () -> Unit, onUse: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = EditsColors.Surface, contentColor = Color.White, modifier = Modifier.testTag("template_sheet")) {
        Column(Modifier.fillMaxWidth().tagsAsResourceIds().padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TemplatePoster(t, Modifier.width(130.dp).aspectRatio(0.72f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("template_sheet_title"))
                    Text(t.tagline, color = EditsColors.TextDim)
                    InfoLine("Format", "${t.aspect.label} · ${t.slotCount} clips · ${formatDuration(t.totalMs)}")
                    InfoLine("Look", Templates.lookName(t))
                    if (t.transitionNames.isNotEmpty()) InfoLine("Transitions", t.transitionNames)
                    t.music?.let { id -> SoundLibrary.byId(id)?.let { InfoLine("Music", "${it.emoji} ${it.label}") } }
                    if (t.usesCaptions) InfoLine("Captions", "Included — edit them after")
                }
            }
            // slot map: one block per clip, width proportional to its length
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                t.slots.forEachIndexed { i, s ->
                    Box(
                        Modifier.weight(s.ms.toFloat()).height(34.dp).clip(RoundedCornerShape(6.dp)).background(Brush.horizontalGradient(t.palette.colors().take(2))),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Text("💡 ${t.tip}", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall)
            PrimaryButton("Use template · pick up to ${t.slotCount} clips", onUse, Modifier.fillMaxWidth(), tag = "template_use")
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(78.dp))
        Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}
