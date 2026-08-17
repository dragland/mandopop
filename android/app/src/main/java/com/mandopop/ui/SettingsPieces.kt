package com.mandopop.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
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
 * button that looks the same either way. Once granted it steps back to a quiet confirmation.
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
            Text("Ready", color = PaleGreen, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
                "Translating selected words needs Accessibility permission.",
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

/**
 * Dictionary credit plus author link, mirroring the extension popup's footer word for word.
 * The popup is the copy of record; change wording on both platforms or neither.
 */
@Composable
internal fun AttributionFooter() {
    // One quiet step above the body color, mirroring the popup's #b3b3b3-over-#8a8a8a register;
    // full PaleGreen is reserved for press/focus feedback.
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = PaleGreen.copy(alpha = 0.7f),
            textDecoration = TextDecoration.Underline,
        ),
        focusedStyle = SpanStyle(color = PaleGreen, textDecoration = TextDecoration.Underline),
        pressedStyle = SpanStyle(color = PaleGreen, textDecoration = TextDecoration.Underline),
    )
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = buildAnnotatedString {
                append("Dictionary data: ")
                withLink(
                    LinkAnnotation.Url(
                        "https://www.mdbg.net/chinese/dictionary?page=cc-cedict",
                        linkStyles,
                    ),
                ) { append("CC-CEDICT") }
                append(", licensed under ")
                withLink(
                    LinkAnnotation.Url(
                        "https://creativecommons.org/licenses/by-sa/4.0/",
                        linkStyles,
                    ),
                    // The license name's spaces are U+00A0: a narrow-screen wrap must not
                    // split "CC BY-SA 4.0".
                ) { append("CC BY-SA 4.0") }
            },
            color = MutedText,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
        )
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = "Open GitHub profile") {
                    uriHandler.openUri("https://github.com/dragland")
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_github),
                contentDescription = null,
                tint = MutedText,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = "@dragland", color = MutedText, fontSize = 11.sp)
        }
    }
}

/** Live sample of a lookup card, so the size slider shows its effect instead of describing it. */
@Composable
internal fun LookupPreview(showAudio: Boolean, fontSize: Int, onPlay: () -> Unit) {
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
                    .border(1.dp, BorderGreen, RoundedCornerShape(22.dp))
                    .clickable(onClick = onPlay)
                    .semantics { contentDescription = "Play 你好" },
                contentAlignment = Alignment.Center,
            ) {
                Text("♪", color = NeonGreen, fontSize = 18.sp)
            }
        }
    }
}
