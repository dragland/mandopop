package com.mandopop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val HackerBlack = Color(0xFF0A0F0A)
internal val PanelBlack = Color(0xFF0D1610)
internal val NeonGreen = Color(0xFF00FF88)
internal val Cyan = Color(0xFF00D4FF)
internal val PaleGreen = Color(0xFFE0FFE8)
internal val MutedText = Color(0xFF7AAA8A)
internal val BorderGreen = Color(0xFF1A3A2A)
internal val ErrorRed = Color(0xFFFF6B6B)

/** Bordered container shared by every settings section. */
@Composable
internal fun SettingsPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PanelBlack, RoundedCornerShape(8.dp))
            .border(1.dp, BorderGreen, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}
