package com.mandopop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mandopop.R
import com.mandopop.briefing.BriefingEngine
import com.mandopop.briefing.ComposerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The daily-briefing feature row: one toggle beside its sibling features, with setup rows
 * appearing beneath it only while it is on and something it needs is missing. Android
 * structurally requires the per-grant settings trips — notification access and usage access
 * are special-access categories with no in-app grant API — so each row deep-links as close to
 * our own toggle as the OS allows. The feature itself lives in the notification; this is only
 * its switch and its health.
 */
@Composable
internal fun BriefingToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
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

    LaunchedEffect(enabled) {
        // composerFor touches the filesystem (models-dir scan) — not main-thread work. Only
        // probed while the feature is on; off means the model machinery stays cold.
        modelStatus = if (enabled) {
            withContext(Dispatchers.Default) { BriefingEngine.composerFor(context).status() }
        } else {
            null
        }
    }

    // Named from the pushed .gguf, not hardcoded — a model swap updates the line.
    val modelName = when (val status = modelStatus) {
        is ComposerStatus.Ready -> status.model
        is ComposerStatus.NotLoaded -> status.model
        else -> null
    }
    ToggleRow(
        icon = R.drawable.ic_notification_due,
        label = "Daily briefing",
        supporting = "A Chinese sentence about your day, in the notification shade" +
            (modelName?.let { " · via $it" } ?: ""),
        checked = enabled,
        onCheckedChange = onEnabledChange,
    )

    if (!enabled) return

    val listenerReady = listenerEnabled && listenerConnected
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
                modifier = Modifier.padding(start = 34.dp),
            )
        }
        is ComposerStatus.Failed -> {
            SetupRow(label = "On-device model", detail = "Engine failed — will retry", onGrant = null)
            Text(
                text = status.message,
                color = ErrorRed,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 34.dp),
            )
        }
        else -> Unit
    }
}

/** Indented under its feature toggle, so setup reads as belonging to it. */
@Composable
private fun SetupRow(label: String, detail: String?, onGrant: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 34.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(label, detail).joinToString(". ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("●", color = MutedText, fontSize = 10.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(text = label, color = PaleGreen, fontSize = 13.sp)
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
