package com.sonuclasses.mitra.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonuclasses.mitra.core.Assistant
import com.sonuclasses.mitra.core.Msg
import com.sonuclasses.mitra.core.Prefs
import com.sonuclasses.mitra.core.VState

private fun stateColor(s: VState) = when (s) {
    VState.LISTENING -> MC.Cyan
    VState.SPEAKING -> MC.Violet
    VState.THINKING -> MC.Pink
    VState.CONNECTING -> MC.Gold
    VState.IDLE -> MC.Violet
}

private fun stateText(s: VState, name: String) = when (s) {
    VState.IDLE -> "माइक दबाइए और $name से बात कीजिए"
    VState.CONNECTING -> "जुड़ रहा हूँ…"
    VState.LISTENING -> "बोलिए, मैं सुन रहा हूँ…"
    VState.THINKING -> "सोच रहा हूँ…"
    VState.SPEAKING -> "$name बोल रहा है… (बीच में बोलकर रोक सकते हैं)"
}

/** Animated orb avatar reacting to the voice level. */
@Composable
fun Orb(state: VState, level: Float, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "orb")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "spin")
    val breathe by inf.animateFloat(0.94f, 1.04f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "breathe")
    val lvl by animateFloatAsState(if (state == VState.IDLE) 0f else level, tween(90), label = "lvl")
    val c = stateColor(state)
    Canvas(modifier) {
        val cx = size.width / 2; val cy = size.height / 2
        val base = minOf(cx, cy) * 0.62f * breathe * (1f + 0.22f * lvl)
        // outer glow
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.35f + 0.3f * lvl), Color.Transparent), Offset(cx, cy), base * 1.65f), base * 1.65f, Offset(cx, cy))
        // rotating ring
        rotate(spin, Offset(cx, cy)) {
            drawCircle(
                Brush.sweepGradient(listOf(MC.Cyan, MC.Violet, MC.Pink, MC.Cyan), Offset(cx, cy)),
                base * 1.18f, Offset(cx, cy), alpha = if (state == VState.IDLE) 0.35f else 0.9f,
                style = Stroke(width = 6f + 10f * lvl)
            )
        }
        // core sphere
        drawCircle(
            Brush.radialGradient(listOf(Color.White, c, Color(0xFF1E1B4B)), Offset(cx - base / 3, cy - base / 3), base * 1.4f),
            base, Offset(cx, cy)
        )
        // inner highlight swirl
        rotate(-spin * 1.5f, Offset(cx, cy)) {
            drawCircle(Brush.radialGradient(listOf(MC.Cyan.copy(alpha = 0.55f), Color.Transparent), Offset(cx + base / 3, cy), base / 1.6f), base / 1.6f, Offset(cx + base / 3, cy))
        }
    }
}

@Composable
fun MessageBubble(m: Msg) {
    val isUser = m.role == "user"
    val isSys = m.role == "system"
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp))
                .background(
                    when {
                        isSys -> Brush.linearGradient(listOf(Color(0x33FBBF24), Color(0x22FBBF24)))
                        isUser -> MC.grad
                        else -> Brush.linearGradient(listOf(MC.Card, MC.Card))
                    }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(m.text, color = if (isSys) MC.Gold else MC.Text, fontSize = 15.sp)
        }
    }
}

@Composable
fun Transcript(messages: List<Msg>, modifier: Modifier = Modifier) {
    val ls = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) ls.animateScrollToItem(messages.size - 1)
    }
    LazyColumn(modifier, state = ls) {
        items(messages, key = { it.id }) { MessageBubble(it) }
    }
}

@Composable
fun HomeScreen(go: (Screen) -> Unit, onMic: () -> Unit) {
    val ctx = LocalContext.current
    val state by Assistant.state.collectAsState()
    val level by Assistant.level.collectAsState()
    val msgs by Assistant.messages.collectAsState()
    val mode by Assistant.modeLabel.collectAsState()
    val ver by Prefs.version.collectAsState()
    val name = remember(ver) { Prefs.personaName }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0D0B1F), MC.Bg))).statusBarsPadding().navigationBarsPadding()) {
        TopBar(title = "$name AI", actions = {
            IconText("🌐") { ctx.startActivity(android.content.Intent(ctx, BrowserActivity::class.java)) }
            IconText("💬") { go(Screen.CHAT) }
            IconText("🛡") { go(Screen.PERMS) }
            IconText("⚙") { go(Screen.SETTINGS) }
        })
        if (mode.isNotEmpty() && state != VState.IDLE) {
            Text(mode, color = MC.Sub, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp))
        }
        Box(Modifier.fillMaxWidth().height(if (msgs.isEmpty()) 300.dp else 210.dp), contentAlignment = Alignment.Center) {
            Orb(state, level, Modifier.size(if (msgs.isEmpty()) 280.dp else 200.dp).clickable { onMic() })
        }
        Text(stateText(state, name), color = MC.Sub, fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        Spacer(Modifier.height(6.dp))
        if (msgs.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("नमस्ते! 🙏", color = MC.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("मैं $name हूँ। कॉल, मैसेज, अलार्म, गाने, खबरें — बस बोलिए।", color = MC.Sub, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
        } else {
            Transcript(msgs, Modifier.weight(1f).fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "🎵 गाना चलाओ" to "YouTube पर कोई बढ़िया हिंदी गाना चलाओ",
                "⏰ अलार्म लगाओ" to "मुझे अलार्म लगाना है",
                "💬 WhatsApp भेजो" to "मुझे WhatsApp पर मैसेज भेजना है",
                "📰 आज की खबर" to "आज की मुख्य खबरें बताओ",
                "📷 क्या दिख रहा है?" to "कैमरे से देखकर बताओ सामने क्या है",
            ).forEach { (label, prompt) ->
                Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, MC.Violet.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .background(MC.Card).clickable { Assistant.sendText(ctx, prompt) }
                    .padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Text(label, color = MC.Text, fontSize = 14.sp)
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(bottom = 16.dp, top = 6.dp), contentAlignment = Alignment.Center) {
            val active = state != VState.IDLE
            Box(
                Modifier.size(84.dp).clip(CircleShape)
                    .background(if (active) Brush.linearGradient(listOf(MC.Pink, MC.Violet)) else MC.grad)
                    .clickable { onMic() },
                contentAlignment = Alignment.Center
            ) { Text(if (active) "■" else "🎤", fontSize = 32.sp, color = Color.White) }
        }
    }
}

@Composable
fun ChatScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val msgs by Assistant.messages.collectAsState()
    val busy by Assistant.busyText.collectAsState()
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(MC.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar("चैट", back = back, actions = { IconText("🗑") { Assistant.clearChat() } })
        if (msgs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("कुछ भी लिखकर पूछिए…", color = MC.Sub)
            }
        } else Transcript(msgs, Modifier.weight(1f).fillMaxWidth())
        if (busy) Row(Modifier.padding(start = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MC.Cyan)
            Text("  सोच रहा हूँ…", color = MC.Sub, fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("संदेश लिखें…") },
                modifier = Modifier.weight(1f), maxLines = 4, shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MC.Violet, unfocusedBorderColor = MC.Card)
            )
            Spacer(Modifier.size(8.dp))
            Box(Modifier.size(52.dp).clip(CircleShape).background(MC.grad).clickable {
                val t = input; input = ""; Assistant.sendText(ctx, t)
            }, contentAlignment = Alignment.Center) { Text("➤", color = Color.White, fontSize = 20.sp) }
        }
    }
}
