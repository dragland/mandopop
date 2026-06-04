package com.mandopop.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mandopop.R
import com.mandopop.dictionary.CedictEntry
import com.mandopop.dictionary.CedictDefinitionFormatter

@Composable
fun OverlayCard(
    entries: List<CedictEntry>,
    showAudio: Boolean,
    chineseFontSizeSp: Int,
    isNoResult: Boolean,
    onSpeak: (String, () -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color.Transparent,
            surface = CardBlack,
            primary = NeonGreen,
            secondary = Cyan,
            onSurface = PaleGreen,
        ),
    ) {
        var playingEntryKey by remember(entries) { mutableStateOf<String?>(null) }
        var playbackToken by remember(entries) { mutableIntStateOf(0) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .shadow(
                        elevation = 20.dp,
                        shape = RoundedCornerShape(8.dp),
                        ambientColor = NeonGreen.copy(alpha = 0.18f),
                        spotColor = NeonGreen.copy(alpha = 0.18f),
                    )
                    .background(CardBlack, RoundedCornerShape(8.dp))
                    .border(1.dp, BorderGreen, RoundedCornerShape(8.dp))
                    .padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val displayEntries = entries.take(3)
                displayEntries.forEachIndexed { index, entry ->
                    val playbackKey = entry.playbackKey()
                    EntryRow(
                        entry = entry,
                        showDefinitions = isNoResult || displayEntries.size > 1,
                        showAudio = showAudio,
                        chineseFontSizeSp = chineseFontSizeSp,
                        isNoResult = isNoResult,
                        isPlaying = playingEntryKey == playbackKey,
                        playbackToken = playbackToken,
                        onSpeak = {
                            val nextPlaybackToken = playbackToken + 1
                            playingEntryKey = playbackKey
                            playbackToken = nextPlaybackToken
                            onSpeak(entry.simplified) {
                                if (
                                    playingEntryKey == playbackKey &&
                                    playbackToken == nextPlaybackToken
                                ) {
                                    playingEntryKey = null
                                }
                            }
                        },
                    )
                    if (index != displayEntries.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 8.dp)
                                .height(1.dp)
                                .background(BorderGreen.copy(alpha = 0.65f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun EntryRow(
    entry: CedictEntry,
    showDefinitions: Boolean,
    showAudio: Boolean,
    chineseFontSizeSp: Int,
    isNoResult: Boolean,
    isPlaying: Boolean,
    playbackToken: Int,
    onSpeak: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = entry.simplified,
                color = if (isNoResult) HotPink else NeonGreen,
                fontSize = chineseFontSizeSp.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Serif,
            )
            Text(
                text = entry.pinyin,
                color = if (isNoResult) HotPink else Cyan,
                fontSize = 14.sp,
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.Monospace,
            )

            if (showDefinitions) {
                Text(
                    text = CedictDefinitionFormatter.formatList(entry.definitions),
                    color = MutedText,
                    fontSize = 12.sp,
                )
            }
        }

        if (showAudio) {
            Spacer(Modifier.width(10.dp))
            AudioButton(
                text = entry.simplified,
                isPlaying = isPlaying,
                playbackToken = playbackToken,
                onClick = onSpeak,
            )
        }
    }
}

@Composable
private fun AudioButton(
    text: String,
    isPlaying: Boolean,
    playbackToken: Int,
    onClick: () -> Unit,
) {
    val progress = remember(text) { Animatable(0f) }

    LaunchedEffect(isPlaying, playbackToken, text) {
        if (!isPlaying) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }

        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = estimatedSpeechMillis(text),
                easing = LinearEasing,
            ),
        )
    }

    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
    ) {
        Box(
            modifier = Modifier.size(30.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = ButtonBlack)
                drawCircle(
                    color = ButtonBorder,
                    style = Stroke(width = 1.dp.toPx()),
                )
                if (progress.value > 0f) {
                    drawArc(
                        color = NeonGreen,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.value,
                        useCenter = false,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            Icon(
                painter = painterResource(R.drawable.ic_volume_up),
                contentDescription = "Play pronunciation for $text",
                tint = if (isPlaying) NeonGreen else IconGray,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun CedictEntry.playbackKey(): String = "$simplified\u0000$pinyin"

private fun estimatedSpeechMillis(text: String): Int {
    return maxOf((text.length * 300 / SPEECH_RATE).toInt(), 400)
}

private const val SPEECH_RATE = 0.85f

private val CardBlack = Color(0xFF0D0D0D)
private val ButtonBlack = Color(0xFF1A1A1A)
private val ButtonBorder = Color(0xFF2A2A2A)
private val BorderGreen = Color(0xFF1A3A2A)
private val NeonGreen = Color(0xFF00FF88)
private val Cyan = Color(0xFF00D4FF)
private val PaleGreen = Color(0xFFE0FFE8)
private val MutedText = Color(0xFF7AAA8A)
private val HotPink = Color(0xFFFF0080)
private val IconGray = Color(0xFF999999)
