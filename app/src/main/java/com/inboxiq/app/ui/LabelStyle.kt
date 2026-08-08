package com.inboxiq.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.inboxiq.app.data.MessageLabel

data class LabelStyle(val displayName: String, val container: Color, val content: Color)

@Composable
fun styleFor(label: MessageLabel): LabelStyle {
    val dark = isSystemInDarkTheme()
    return when (label) {
        MessageLabel.PERSONAL -> if (dark) {
            LabelStyle("Personal", Color(0xFF1E4025), Color(0xFF8FE0A3))
        } else {
            LabelStyle("Personal", Color(0xFFE3F2E5), Color(0xFF1E6B33))
        }
        MessageLabel.WORK -> if (dark) {
            LabelStyle("Work", Color(0xFF262C55), Color(0xFFAEB6F5))
        } else {
            LabelStyle("Work", Color(0xFFE6E9FB), Color(0xFF3A47B8))
        }
        MessageLabel.PROMO -> if (dark) {
            LabelStyle("Promo", Color(0xFF4A3B10), Color(0xFFF2CB6C))
        } else {
            LabelStyle("Promo", Color(0xFFFFF3D9), Color(0xFF9A6B00))
        }
        MessageLabel.OTP_2FA -> if (dark) {
            LabelStyle("Code", Color(0xFF123B42), Color(0xFF8FDCEA))
        } else {
            LabelStyle("Code", Color(0xFFDFF3F6), Color(0xFF116877))
        }
        MessageLabel.SPAM -> if (dark) {
            LabelStyle("Spam", Color(0xFF4A241C), Color(0xFFF0A28E))
        } else {
            LabelStyle("Spam", Color(0xFFFBE1DD), Color(0xFFA33A26))
        }
        MessageLabel.SCAM -> if (dark) {
            LabelStyle("Scam", Color(0xFF4A1229), Color(0xFFF28FB2))
        } else {
            LabelStyle("Scam", Color(0xFFF8D6DD), Color(0xFFB0184B))
        }
        MessageLabel.UNLABELED -> LabelStyle(
            "Unlabeled",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
