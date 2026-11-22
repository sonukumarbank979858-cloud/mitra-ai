package com.sonuclasses.mitra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object MC {
    val Bg = Color(0xFF07070F)
    val Surface = Color(0xFF12121F)
    val Card = Color(0xFF181829)
    val Violet = Color(0xFF8B5CF6)
    val Cyan = Color(0xFF22D3EE)
    val Pink = Color(0xFFF472B6)
    val Gold = Color(0xFFFBBF24)
    val Green = Color(0xFF34D399)
    val Text = Color(0xFFF1F0FF)
    val Sub = Color(0xFFA1A1C2)
    val grad = Brush.linearGradient(listOf(Violet, Color(0xFF6366F1), Cyan))
}

@Composable
fun MitraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MC.Violet, secondary = MC.Cyan, tertiary = MC.Pink,
            background = MC.Bg, surface = MC.Surface, onBackground = MC.Text, onSurface = MC.Text,
            surfaceVariant = MC.Card, onSurfaceVariant = MC.Sub
        ),
        content = content
    )
}

/** Simple top bar: back arrow (optional), title, trailing actions. */
@Composable
fun TopBar(title: String, back: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (back != null) {
            IconText("←", onClick = back)
            Box(Modifier.size(8.dp))
        }
        Text(title, color = MC.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
    }
}

@Composable
fun IconText(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(MC.Card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(glyph, fontSize = 18.sp, color = MC.Text) }
}

@Composable
fun SectionCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp)).background(MC.Card).padding(16.dp)
    ) { content() }
}

@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(if (enabled) MC.grad else Brush.linearGradient(listOf(MC.Card, MC.Card)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
}
