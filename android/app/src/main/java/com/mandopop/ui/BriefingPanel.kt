package com.mandopop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mandopop.briefing.BriefingEngine
import com.mandopop.briefing.ComposerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The daily-briefing section, following the accessibility card's lesson: when everything works
 * it collapses to one quiet Ready line, and setup rows appear only for what is actually
 * missing. Android structurally requires the per-grant trips — notification access and usage
 * access are special-access categories with no in-app grant API — so each row deep-links as
 * close to our own toggle as the OS allows. The briefing itself lives in the notification;
 * this panel is only its health.
 */
@Composable
internal fun BriefingPanel(
    listenerEnabled: Boolean,
    listenerConnected: Boolean,
    calendarGranted: Boolean,
    usageAccessGranted: Boolean,
    onOpenNotificationAccess: () -> Unit,
    onRequestCalendar: () -> Unit,
    onOpenUsageAccess: () -> Unit,
) {
    val context = LocalContext.current
    var modelStatus by remember { mutableStateOf<ComposerStatus?>(null) }

    LaunchedEffect(Unit) {
        // composerFor touches the filesystem (models-dir scan) — not main-thread work.
        modelStatus = withContext(Dispatchers.Default) {
            BriefingEngine.composerFor(context).status()
        }
    }

    val listenerReady = listenerEnabled && listenerConnected
    val modelReady = modelStatus is ComposerStatus.Ready || modelStatus is ComposerStatus.NotLoaded
    val allReady = listenerReady && calendarGranted && usageAccessGranted && modelReady

    SettingsPanel {
        Text(
            text = "One short Chinese sentence about your actual day — calendar, notifications " +
                "and the screen you were just reading — refreshed when you pull down the " +
                "shade. Composed and verified on this phone; nothing you read or receive " +
                "ever leaves it.",
            color = MutedText,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )

        if (allReady) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = NeonGreen, fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                Text("Ready", color = PaleGreen, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                (modelStatus as? ComposerStatus.Ready)?.let {
                    Text(it.model, color = MutedText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
            return@SettingsPanel
        }

        // Only what is missing, each with the most direct grant path Android allows.
        if (!listenerReady) {
            SetupRow(
                label = "Notification access",
                detail = if (listenerEnabled) "Granted, not connected — re-toggle it" else null,
                onGrant = onOpenNotificationAccess,
            )
        }
        if (!calendarGranted) {
            SetupRow(label = "Calendar", detail = null, onGrant = onRequestCalendar)
        }
        if (!usageAccessGranted) {
            SetupRow(
                label = "Usage access",
                detail = "For the 今天学了 N 分钟 line",
                onGrant = onOpenUsageAccess,
            )
        }
        when (val status = modelStatus) {
            is ComposerStatus.MissingModel -> {
                SetupRow(label = "On-device model", detail = "Not installed", onGrant = null)
                Text(
                    text = "adb push ${status.expectedPath}",
                    color = MutedText,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            is ComposerStatus.Failed -> {
                SetupRow(label = "On-device model", detail = "Engine failed — will retry", onGrant = null)
                Text(
                    text = status.message,
                    color = ErrorRed,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun SetupRow(label: String, detail: String?, onGrant: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(label, detail).joinToString(". ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("●", color = MutedText, fontSize = 11.sp)
        Spacer(Modifier.width(10.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(text = label, color = PaleGreen, fontSize = 14.sp)
            detail?.let {
                Text(text = it, color = MutedText, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        onGrant?.let {
            TextButton(
                onClick = it,
                modifier = Modifier.semantics { contentDescription = "Grant $label" },
            ) {
                Text("Grant", color = NeonGreen, fontSize = 13.sp)
            }
        }
    }
}
