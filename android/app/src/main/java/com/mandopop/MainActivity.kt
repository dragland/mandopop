package com.mandopop

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mandopop.settings.SettingsStore
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val settingsStore by lazy { SettingsStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MandopopSettingsApp(
                settingsStore = settingsStore,
                openAccessibilitySettings = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
            )
        }
    }
}

@Composable
private fun MandopopSettingsApp(
    settingsStore: SettingsStore,
    openAccessibilitySettings: () -> Unit,
) {
    val initial = remember { settingsStore.snapshot() }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var showAudio by remember { mutableStateOf(initial.showAudio) }
    var playfulNoResult by remember { mutableStateOf(initial.playfulNoResult) }
    var fontSize by remember { mutableFloatStateOf(initial.chineseFontSizeSp.toFloat()) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = HackerBlack,
            surface = PanelBlack,
            primary = NeonGreen,
            secondary = Cyan,
            onBackground = PaleGreen,
            onSurface = PaleGreen,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HackerBlack)
                .padding(20.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Header()

                Button(
                    onClick = openAccessibilitySettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonGreen,
                        contentColor = HackerBlack,
                    ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_accessibility),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Open Accessibility Settings", fontWeight = FontWeight.SemiBold)
                }

                SettingsPanel {
                    ToggleRow(
                        icon = R.drawable.ic_translate,
                        label = "Enable Lookups",
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            settingsStore.setEnabled(it)
                        },
                    )

                    ToggleRow(
                        icon = R.drawable.ic_voice,
                        label = "Audio Button",
                        checked = showAudio,
                        onCheckedChange = {
                            showAudio = it
                            settingsStore.setShowAudio(it)
                        },
                    )

                    ToggleRow(
                        icon = R.drawable.ic_translate,
                        label = "Playful Misses",
                        checked = playfulNoResult,
                        onCheckedChange = {
                            playfulNoResult = it
                            settingsStore.setPlayfulNoResult(it)
                        },
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_text_fields),
                                contentDescription = null,
                                tint = Cyan,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Chinese Size",
                                color = PaleGreen,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${fontSize.roundToInt()}sp",
                                color = NeonGreen,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                            )
                        }
                        Slider(
                            value = fontSize,
                            onValueChange = {
                                val rounded = (it / 2f).roundToInt() * 2f
                                fontSize = rounded.coerceIn(
                                    SettingsStore.MIN_FONT_SIZE_SP.toFloat(),
                                    SettingsStore.MAX_FONT_SIZE_SP.toFloat(),
                                )
                            },
                            onValueChangeFinished = {
                                settingsStore.setChineseFontSizeSp(fontSize)
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "Chinese character size"
                                stateDescription = "${fontSize.roundToInt()}sp"
                            },
                            valueRange = SettingsStore.MIN_FONT_SIZE_SP.toFloat()..
                                SettingsStore.MAX_FONT_SIZE_SP.toFloat(),
                            steps = 9,
                            colors = SliderDefaults.colors(
                                thumbColor = NeonGreen,
                                activeTrackColor = NeonGreen,
                                inactiveTrackColor = BorderGreen,
                            ),
                        )
                    }
                }

                PreviewCard(
                    showAudio = showAudio,
                    fontSize = fontSize.roundToInt(),
                )
            }
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Mandopop",
            color = PaleGreen,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "學",
            color = NeonGreen,
            fontSize = 40.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Serif,
        )
    }
}

@Composable
private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBlack, RoundedCornerShape(8.dp))
            .border(1.dp, BorderGreen, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

@Composable
private fun ToggleRow(
    @DrawableRes icon: Int,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onValueChange = onCheckedChange,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = if (checked) "On" else "Off"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Cyan,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            color = PaleGreen,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NeonGreen,
                checkedTrackColor = NeonGreen.copy(alpha = 0.35f),
                uncheckedThumbColor = MutedText,
                uncheckedTrackColor = BorderGreen,
            ),
        )
    }
}

@Composable
private fun PreviewCard(showAudio: Boolean, fontSize: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D0D0D), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "你好",
                    color = NeonGreen,
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Serif,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "nǐ hǎo",
                    color = Cyan,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = "hello; hi",
                color = MutedText,
                fontSize = 12.sp,
            )
        }
        if (showAudio) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .border(1.dp, BorderGreen, RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("♪", color = NeonGreen, fontSize = 18.sp)
            }
        }
    }
}

private val HackerBlack = Color(0xFF0A0F0A)
private val PanelBlack = Color(0xFF0D1610)
private val NeonGreen = Color(0xFF00FF88)
private val Cyan = Color(0xFF00D4FF)
private val PaleGreen = Color(0xFFE0FFE8)
private val MutedText = Color(0xFF7AAA8A)
private val BorderGreen = Color(0xFF1A3A2A)
