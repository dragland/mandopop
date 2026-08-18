package com.mandopop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.genai.common.DownloadStatus
import com.mandopop.briefing.BriefingEngine
import com.mandopop.briefing.GeminiNanoComposer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The daily-briefing section: whether its inputs are granted, whether the on-device model is
 * present, and — the reason this panel is this verbose — a test bench for the model audition.
 * Raw model output and every verifier rejection are shown, because the composer decision
 * (Nano vs a bundled model) is made by reading exactly this.
 */
@Composable
internal fun BriefingPanel(
    listenerEnabled: Boolean,
    calendarGranted: Boolean,
    onOpenNotificationAccess: () -> Unit,
    onRequestCalendar: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var modelStatus by remember { mutableStateOf<GeminiNanoComposer.Status?>(null) }
    var downloadProgress by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var briefing by remember { mutableStateOf(BriefingEngine.current) }
    var attempt by remember { mutableStateOf(BriefingEngine.lastAttempt) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        modelStatus = BriefingEngine.composer.status()
    }

    SettingsPanel {
        Text(
            text = "One short Chinese sentence about your actual day — calendar, notifications " +
                "and the screen you were just reading — regenerated when you pull down the " +
                "shade. Composed on-device and verified against your course vocabulary; " +
                "nothing you read or receive ever leaves the phone.",
            color = MutedText,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )

        StatusRow(
            label = "Notification access",
            ok = listenerEnabled,
            action = "Grant",
            onAction = onOpenNotificationAccess,
        )
        StatusRow(
            label = "Calendar",
            ok = calendarGranted,
            action = "Grant",
            onAction = onRequestCalendar,
        )
        StatusRow(
            label = "Gemini Nano",
            ok = modelStatus == GeminiNanoComposer.Status.AVAILABLE,
            okText = when (modelStatus) {
                GeminiNanoComposer.Status.AVAILABLE -> "Ready"
                GeminiNanoComposer.Status.DOWNLOADABLE -> "Not downloaded"
                GeminiNanoComposer.Status.DOWNLOADING -> "Downloading…"
                GeminiNanoComposer.Status.UNAVAILABLE -> "Unavailable — template fallback"
                null -> "Checking…"
            },
            action = if (modelStatus == GeminiNanoComposer.Status.DOWNLOADABLE) "Download" else null,
            onAction = {
                scope.launch {
                    error = null
                    try {
                        BriefingEngine.composer.download().collect { status ->
                            downloadProgress = when (status) {
                                is DownloadStatus.DownloadStarted -> "starting…"
                                is DownloadStatus.DownloadProgress ->
                                    "%.0f MB".format(status.totalBytesDownloaded / 1e6)
                                is DownloadStatus.DownloadCompleted -> null
                                is DownloadStatus.DownloadFailed -> {
                                    error = "Model download failed: ${status.e.message}"
                                    null
                                }
                                else -> downloadProgress
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        error = "Model download failed: ${failure.message}"
                    }
                    modelStatus = BriefingEngine.composer.status()
                }
            },
        )
        downloadProgress?.let {
            Text(text = "Downloading Gemini Nano… $it", color = MutedText, fontSize = 12.sp)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            BriefingEngine.refresh(context.applicationContext, force = true)
                            briefing = BriefingEngine.current
                            attempt = BriefingEngine.lastAttempt
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Exception) {
                            error = failure.message ?: "Briefing generation failed"
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonGreen,
                    contentColor = HackerBlack,
                ),
            ) {
                Text("Generate now", fontWeight = FontWeight.SemiBold)
            }
            if (busy) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = NeonGreen,
                    strokeWidth = 2.dp,
                )
            }
        }

        briefing?.let {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = it.sentence,
                    color = NeonGreen,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Serif,
                )
                it.frontier?.takeIf { f -> it.sentence.contains(f.hanzi) }?.let { f ->
                    Text(
                        text = listOfNotNull(f.hanzi, f.pinyin, f.english).joinToString(" — "),
                        color = Cyan,
                        fontSize = 13.sp,
                    )
                }
                Text(
                    text = when (it.source) {
                        BriefingEngine.Source.NANO -> "composed by Gemini Nano"
                        BriefingEngine.Source.TEMPLATE -> "template fallback"
                    },
                    color = MutedText,
                    fontSize = 11.sp,
                )
            }
        }

        // The audition readout. Everything the pipeline did, verbatim — a rejected model output
        // is the single most informative artifact this screen can show.
        attempt?.let { a ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DebugLine("inputs", a.inputsSummary)
                a.gist?.let { DebugLine("gist", it) }
                a.promptWords?.let { DebugLine("words", it.joinToString(" ")) }
                a.modelOutputs.forEachIndexed { i, raw ->
                    DebugLine("nano #${i + 1}", raw)
                }
                a.rejections.forEach { DebugLine("rejected", it, ErrorRed) }
                DebugLine("outcome", a.outcome)
            }
        }

        error?.let {
            Text(text = it, color = ErrorRed, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    ok: Boolean,
    action: String?,
    onAction: () -> Unit,
    okText: String? = null,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("●", color = if (ok) NeonGreen else MutedText, fontSize = 11.sp)
        Spacer(Modifier.width(10.dp))
        Text(text = label, color = PaleGreen, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            text = okText ?: if (ok) "Ready" else "Not granted",
            color = MutedText,
            fontSize = 12.sp,
        )
        if (!ok && action != null) {
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onAction) {
                Text(action, color = NeonGreen, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DebugLine(tag: String, value: String, color: androidx.compose.ui.graphics.Color = MutedText) {
    Text(
        text = "$tag: $value",
        color = color,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontFamily = FontFamily.Monospace,
    )
}
