package com.mandopop

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
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
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.mandopop.briefing.NotificationCatcher
import com.mandopop.notification.DueNotifier
import com.mandopop.service.TextSelectionService
import com.mandopop.tts.ChineseTtsManager
import com.mandopop.settings.SettingsStore
import com.mandopop.ui.BorderGreen
import com.mandopop.ui.Cyan
import com.mandopop.ui.HackerBlack
import com.mandopop.ui.MutedText
import com.mandopop.ui.NeonGreen
import com.mandopop.ui.PaleGreen
import com.mandopop.ui.PanelBlack
import com.mandopop.ui.AttributionFooter
import com.mandopop.ui.LookupPreview
import com.mandopop.ui.SectionLabel
import com.mandopop.ui.ServiceStatusCard
import com.mandopop.ui.BriefingPanel
import com.mandopop.ui.SettingsPanel
import com.mandopop.ui.ToggleRow
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

    /** Drives the sample card's play button, so the pronunciation toggle demonstrates itself. */
    private val tts by lazy { ChineseTtsManager(applicationContext) }

    /**
     * Whether Android has granted the accessibility permission lookups depend on.
     *
     * Re-read on every resume, because the user grants it by leaving for system settings and
     * coming back — there is no callback for it.
     */
    private var serviceEnabled by mutableStateOf(false)

    // Same resume-time pattern as the accessibility grant: both are given in system settings
    // with no callback, so they are simply re-read whenever the user comes back.
    private var notificationListenerEnabled by mutableStateOf(false)
    private var notificationListenerConnected by mutableStateOf(false)
    private var calendarGranted by mutableStateOf(false)

    private val calendarPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            calendarGranted = granted
            // Permanently denied ("don't ask again") makes launch() return false instantly with
            // no dialog — a dead end with zero feedback. Send the user where the switch actually
            // is, the same move the accessibility card makes.
            if (!granted &&
                !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)
            ) {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null),
                    ),
                )
            }
        }

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

        // Off the main thread: the signed-in check decrypts a stored token and hits disk.
        lifecycleScope.launch {
            if (withContext(Dispatchers.IO) { traverseSync.isSignedIn() }) {
                SyncWorker.ensureScheduled(applicationContext)
            }
        }

        setContent {
            MandopopSettingsApp(
                settingsStore = settingsStore,
                traverseSync = traverseSync,
                serviceEnabled = serviceEnabled,
                openAccessibilitySettings = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                requestNotificationPermission = ::requestNotificationPermission,
                playPreview = { tts.speak("你好") },
                notificationListenerEnabled = notificationListenerEnabled,
                notificationListenerConnected = notificationListenerConnected,
                calendarGranted = calendarGranted,
                openNotificationAccessSettings = {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                requestCalendarPermission = {
                    calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled = isLookupServiceEnabled()
        notificationListenerEnabled = NotificationCatcher.isEnabled(this)
        notificationListenerConnected = NotificationCatcher.isConnected()
        // Granted-but-unbound recovers with a rebind request far more often than with the
        // user re-toggling access in system settings.
        NotificationCatcher.requestRebindIfNeeded(this)
        calendarGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        // Rebuild the notification whenever the app is opened. Cheap (local counts only) and it
        // means a stale or swiped-away notification is never more than an app launch from correct,
        // instead of waiting up to 15 minutes for the next periodic sync.
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (!traverseSync.isSignedIn()) return@withContext null
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

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }

    private fun isLookupServiceEnabled(): Boolean {
        val expected = ComponentName(this, TextSelectionService::class.java).flattenToString()
        return Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':').any { it.equals(expected, ignoreCase = true) }
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
    serviceEnabled: Boolean,
    openAccessibilitySettings: () -> Unit,
    requestNotificationPermission: () -> Unit,
    playPreview: () -> Unit,
    notificationListenerEnabled: Boolean,
    notificationListenerConnected: Boolean,
    calendarGranted: Boolean,
    openNotificationAccessSettings: () -> Unit,
    requestCalendarPermission: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val initial = remember { settingsStore.snapshot() }
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
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Header()

                ServiceStatusCard(
                    serviceEnabled = serviceEnabled,
                    onOpenSettings = openAccessibilitySettings,
                )

                SectionLabel("Lookups")
                SettingsPanel {
                    ToggleRow(
                        icon = R.drawable.ic_voice,
                        label = "Pronunciation",
                        supporting = "Add a button to hear the word spoken",
                        checked = showAudio,
                        onCheckedChange = {
                            showAudio = it
                            settingsStore.setShowAudio(it)
                        },
                    )

                    ToggleRow(
                        icon = R.drawable.ic_sparkle,
                        label = "Playful misses",
                        supporting = "Reply with a Mandarin phrase when a word isn't in the dictionary",
                        checked = playfulNoResult,
                        onCheckedChange = {
                            playfulNoResult = it
                            settingsStore.setPlayfulNoResult(it)
                        },
                    )
                }

                SectionLabel("Hanzi size")
                SettingsPanel {
                    LookupPreview(
                        showAudio = showAudio,
                        fontSize = fontSize.roundToInt(),
                        onPlay = playPreview,
                    )
                    Slider(
                        value = fontSize,
                        onValueChange = {
                            val rounded = (it / 2f).roundToInt() * 2f
                            fontSize = rounded.coerceIn(
                                SettingsStore.MIN_FONT_SIZE_SP.toFloat(),
                                SettingsStore.MAX_FONT_SIZE_SP.toFloat(),
                            )
                        },
                        onValueChangeFinished = { settingsStore.setChineseFontSizeSp(fontSize) },
                        modifier = Modifier.semantics {
                            contentDescription = "Hanzi size"
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

                SectionLabel("Connected courses")
                TraversePanel(
                    sync = traverseSync,
                    requestNotificationPermission = requestNotificationPermission,
                )

                SectionLabel("Daily briefing")
                BriefingPanel(
                    listenerEnabled = notificationListenerEnabled,
                    listenerConnected = notificationListenerConnected,
                    calendarGranted = calendarGranted,
                    onOpenNotificationAccess = openNotificationAccessSettings,
                    onRequestCalendar = requestCalendarPermission,
                )

                AttributionFooter()
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

