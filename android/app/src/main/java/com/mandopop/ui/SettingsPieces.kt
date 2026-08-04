package com.mandopop.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mandopop.R

/** Quiet label above a group, so the screen reads as sections rather than one long list. */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = MutedText,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 4.dp),
    )
}

/**
 * Whether lookups actually work right now.
 *
 * The app is inert until Android grants the accessibility permission, and that is invisible from
 * inside the app — so this states it outright rather than offering an ambiguous "open settings"
 * button that looks the same either way. Once granted it steps back to a quiet confirmation
 * instead of continuing to shout.
 */
@Composable
internal fun ServiceStatusCard(serviceEnabled: Boolean, onOpenSettings: () -> Unit) {
    if (serviceEnabled) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelBlack, RoundedCornerShape(8.dp))
                .border(1.dp, BorderGreen, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("●", color = NeonGreen, fontSize = 12.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Ready", color = PaleGreen, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Select English text in any app",
                    color = MutedText,
                    fontSize = 13.sp,
                )
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBlack, RoundedCornerShape(8.dp))
            .border(1.dp, NeonGreen, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Finish setup",
                color = PaleGreen,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Mandopop reads the word you select, which Android only allows through " +
                    "Accessibility. Lookups stay off until you turn it on.",
                color = MutedText,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth().height(48.dp),
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
            Text("Turn on Mandopop", fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Settings row. [supporting] carries the explanation, so labels stay short. */
@Composable
internal fun ToggleRow(
    @DrawableRes icon: Int,
    label: String,
    supporting: String,
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
                contentDescription = "$label. $supporting"
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
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = label, color = PaleGreen, fontSize = 15.sp)
            Text(text = supporting, color = MutedText, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
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

/** Live sample of a lookup card, so the size slider shows its effect instead of describing it. */
@Composable
internal fun LookupPreview(showAudio: Boolean, fontSize: Int) {
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
            Text(text = "hello; hi", color = MutedText, fontSize = 12.sp)
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
