package com.mandopop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mandopop.R
import com.mandopop.notification.DueNotifier
import com.mandopop.traverse.SyncOutcome
import com.mandopop.traverse.TraverseSync
import com.mandopop.work.SyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TraversePanel(
    sync: TraverseSync,
    requestNotificationPermission: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var signedIn by remember { mutableStateOf(sync.isSignedIn) }
    var email by remember { mutableStateOf(sync.signedInEmail.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dueCount by remember { mutableIntStateOf(-1) }

    LaunchedEffect(signedIn) {
        if (!signedIn) return@LaunchedEffect
        // Seed from the local mirror so reopening the app shows the count immediately, without
        // waiting for a network round trip.
        runCatching {
            error = sync.state().lastError
            dueCount = sync.localDueCount()
        }
    }

    SettingsPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_translate),
                contentDescription = null,
                tint = Cyan,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Traverse Account",
                color = PaleGreen,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = NeonGreen,
                    strokeWidth = 2.dp,
                )
            }
        }

        if (signedIn) {
            Text(
                text = email.ifBlank { "Signed in" },
                color = MutedText,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (dueCount >= 0) {
                Text(
                    text = "$dueCount due today",
                    color = NeonGreen,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            status = null
                            try {
                                val outcome = sync.sync(force = true)
                                when (outcome) {
                                    is SyncOutcome.Success -> {
                                        dueCount = outcome.dueCount
                                        status = "Synced ${outcome.liveCount} cards"
                                    }
                                    is SyncOutcome.Failure -> error = outcome.message
                                    is SyncOutcome.NotSignedIn -> signedIn = false
                                }
                                DueNotifier.show(context, outcome)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: Exception) {
                                error = failure.message ?: "Sync failed"
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
                    Text("Sync Now", fontWeight = FontWeight.SemiBold)
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            // Cancel the worker and the notification even if clearing the local
                            // mirror fails, so sign-out never leaves a live counter behind.
                            try {
                                runCatching { sync.signOut() }
                                SyncWorker.cancelAll(context)
                                DueNotifier.cancel(context)
                            } finally {
                                signedIn = false
                                password = ""
                                dueCount = -1
                                status = null
                                error = null
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                ) {
                    Text("Sign Out", color = MutedText)
                }
            }
        } else {
            TraverseTextField(
                value = email,
                onValueChange = { email = it },
                label = "Traverse email",
                keyboardType = KeyboardType.Email,
            )
            TraverseTextField(
                value = password,
                onValueChange = { password = it },
                label = "Password",
                keyboardType = KeyboardType.Password,
                isPassword = true,
            )
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        status = null
                        try {
                            sync.signIn(email.trim(), password)
                            password = ""
                            signedIn = true
                            requestNotificationPermission()
                            SyncWorker.ensureScheduled(context)
                            when (val outcome = sync.sync(force = true)) {
                                is SyncOutcome.Success -> {
                                    dueCount = outcome.dueCount
                                    status = "Synced ${outcome.liveCount} cards"
                                    DueNotifier.show(context, outcome)
                                }
                                is SyncOutcome.Failure -> error = outcome.message
                                is SyncOutcome.NotSignedIn -> signedIn = false
                            }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Exception) {
                            error = failure.message ?: "Sign-in failed"
                        }
                        busy = false
                    }
                },
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonGreen,
                    contentColor = HackerBlack,
                    disabledContainerColor = BorderGreen,
                    disabledContentColor = MutedText,
                ),
            ) {
                Text("Sign In with Traverse", fontWeight = FontWeight.SemiBold)
            }
        }

        status?.let {
            Text(text = it, color = MutedText, fontSize = 12.sp)
        }
        error?.let {
            Text(
                text = it,
                color = ErrorRed,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun TraverseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    isPassword: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 13.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (isPassword) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = PaleGreen,
            unfocusedTextColor = PaleGreen,
            focusedBorderColor = NeonGreen,
            unfocusedBorderColor = BorderGreen,
            focusedLabelColor = NeonGreen,
            unfocusedLabelColor = MutedText,
            cursorColor = NeonGreen,
        ),
    )
}

