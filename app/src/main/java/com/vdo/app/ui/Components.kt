package com.vdo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Large screen title with optional actions on the right. */
@Composable
fun ScreenTitle(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, action: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White, modifier = Modifier.weight(1f))
        action()
    }
}

/**
 * Artwork tile: a tinted placeholder with an icon, the real image on top once it loads,
 * and an optional "watched" bar along the bottom edge.
 */
@Composable
fun Thumb(
    model: Any?,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    corner: Dp = 14.dp,
    watched: Float = 0f,
    badge: String? = null,
) {
    Box(
        modifier.clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(Color(0xFF2A2F7A), Color(0xFF1A1F55)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color(0x99E9B59C), modifier = Modifier.size(28.dp))
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (badge != null) {
            Text(
                badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC000000)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        if (watched > 0.01f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66000000))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(watched).background(VdoColors.RoseGold))
            }
        }
    }
}

@Composable
fun Empty(icon: ImageVector, title: String, body: String, action: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(32.dp)).background(VdoColors.Surface),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = VdoColors.Violet, modifier = Modifier.size(44.dp)) }
        Spacer(Modifier.height(20.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
        Text(body, color = VdoColors.Muted, textAlign = TextAlign.Center, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
        action()
    }
}
