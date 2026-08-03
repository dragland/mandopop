package com.mandopop

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.lifecycle.lifecycleScope
import com.mandopop.notification.DueNotifier
import com.mandopop.settings.SettingsStore
import com.mandopop.ui.BorderGreen
import com.mandopop.ui.Cyan
import com.mandopop.ui.HackerBlack
import com.mandopop.ui.MutedText
import com.mandopop.ui.NeonGreen
import com.mandopop.ui.PaleGreen
import com.mandopop.ui.PanelBlack
import com.mandopop.ui.SettingsPanel
import com.mandopop.ui.TraversePanel
import com.mandopop.traverse.TraverseSync
import com.mandopop.work.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val settingsStore by lazy { SettingsStore(applicationContext) }
    private val traverseSync by lazy { TraverseSync(applicationContext) }

    // Sign-in posts the first notification before the permission dialog is answered, so it gets
    // dropped. Re-post once the user grants it, otherwise nothing appears until the worker runs.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) return@registerForActivityResult
            lifecycleScope.launch {
                runCatching { traverseSync.sync() }
                    .onSuccess { DueNotifier.show(applicationContext, it) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 draws every app edge-to-edge whether it opts in or not, so declare it and
        // pad by the insets rather than letting the header slide under the status bar.
        enableEdgeToEdge()

        // Off the main thread: the signed-in check decrypts from the keystore, which is a binder
        // round trip plus a disk read.
        lifecycleScope.launch {
            if (withContext(Dispatchers.IO) { traverseSync.isSignedIn }) {
                SyncWorker.ensureScheduled(applicationContext)
            }
        }

        setContent {
            MandopopSettingsApp(
                settingsStore = settingsStore,
                traverseSync = traverseSync,
                openAccessibilitySettings = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                requestNotificationPermission = ::requestNotificationPermission,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Rebuild the notification whenever the app is opened. Cheap (local counts only) and it
        // means a stale or swiped-away notification is never more than an app launch from correct,
        // instead of waiting up to 15 minutes for the next periodic sync.
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (!traverseSync.isSignedIn) return@withContext null
                    Triple(
                        traverseSync.localDueCount(),
                        traverseSync.localLiveCount(),
                        traverseSync.localExample(),
                    )
                }
            }.getOrNull()?.let { (due, live, example) ->
                DueNotifier.repost(applicationContext, due, live, example)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun MandopopSettingsApp(
    settingsStore: SettingsStore,
    traverseSync: TraverseSync,
    openAccessibilitySettings: () -> Unit,
    requestNotificationPermission: () -> Unit,
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
                .safeDrawingPadding()
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

                TraversePanel(
                    sync = traverseSync,
                    requestNotificationPermission = requestNotificationPermission,
                )
            }
        }
    }
}

/**
 * Traverse account section.
 *
 * The password is held only in this composable's state long enough to exchange it for a refresh
 * token, then cleared. Sync failures are shown here verbatim rather than summarised, because a
 * silently stale card count is impossible to debug on a real device.
 */
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
            text = "学",
            color = NeonGreen,
            fontSize = 40.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Serif,
        )
    }
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
