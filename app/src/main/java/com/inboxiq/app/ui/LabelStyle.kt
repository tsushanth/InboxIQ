package com.inboxiq.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.inbox.InboxTab
import com.inboxiq.app.ui.theme.LocalDarkTheme

/**
 * A label is always its name plus an icon plus a colour, never colour alone. Contrast of each
 * [content] on its [container] is at least 4.5:1 (checked in LabelStyleContrastTest).
 */
data class LabelStyle(val displayName: String, val icon: ImageVector, val container: Color, val content: Color)

object LabelPalette {
    data class Pair2(val container: Color, val content: Color)

    fun light(label: MessageLabel): Pair2 = when (label) {
        MessageLabel.PERSONAL -> Pair2(Color(0xFFDDEFE6), Color(0xFF14573A))
        MessageLabel.WORK -> Pair2(Color(0xFFDFE7F7), Color(0xFF254586))
        MessageLabel.PROMO -> Pair2(Color(0xFFF8EACB), Color(0xFF7A4E00))
        MessageLabel.OTP_2FA -> Pair2(Color(0xFFE8E1F6), Color(0xFF50358F))
        MessageLabel.SPAM -> Pair2(Color(0xFFEBE4DC), Color(0xFF5E4F40))
        MessageLabel.SCAM -> Pair2(Color(0xFFF9DDDA), Color(0xFF9A1B15))
        MessageLabel.UNLABELED -> Pair2(Color(0xFFE4E8E6), Color(0xFF4D5B64))
    }

    fun dark(label: MessageLabel): Pair2 = when (label) {
        MessageLabel.PERSONAL -> Pair2(Color(0xFF16402D), Color(0xFF9EE0BE))
        MessageLabel.WORK -> Pair2(Color(0xFF1F3158), Color(0xFFB4C6F2))
        MessageLabel.PROMO -> Pair2(Color(0xFF4A3608), Color(0xFFF2CB78))
        MessageLabel.OTP_2FA -> Pair2(Color(0xFF33265B), Color(0xFFD0C2F2))
        MessageLabel.SPAM -> Pair2(Color(0xFF3B3229), Color(0xFFD9C9B8))
        MessageLabel.SCAM -> Pair2(Color(0xFF5A1A16), Color(0xFFFAB8B2))
        MessageLabel.UNLABELED -> Pair2(Color(0xFF28323A), Color(0xFFB3C0C8))
    }

    fun displayName(label: MessageLabel) = when (label) {
        MessageLabel.PERSONAL -> "Personal"
        MessageLabel.WORK -> "Work"
        MessageLabel.PROMO -> "Promo"
        MessageLabel.OTP_2FA -> "Code"
        MessageLabel.SPAM -> "Spam"
        MessageLabel.SCAM -> "Scam"
        MessageLabel.UNLABELED -> "Not sorted yet"
    }

    fun icon(label: MessageLabel): ImageVector = when (label) {
        MessageLabel.PERSONAL -> Icons.Filled.Person
        MessageLabel.WORK -> Icons.Filled.Work
        MessageLabel.PROMO -> Icons.Filled.LocalOffer
        MessageLabel.OTP_2FA -> Icons.Filled.Key
        MessageLabel.SPAM -> Icons.Filled.Report
        MessageLabel.SCAM -> Icons.Filled.Warning
        MessageLabel.UNLABELED -> Icons.Filled.Inbox
    }
}

@Composable
fun styleFor(label: MessageLabel): LabelStyle {
    val p = if (LocalDarkTheme.current) LabelPalette.dark(label) else LabelPalette.light(label)
    return LabelStyle(LabelPalette.displayName(label), LabelPalette.icon(label), p.container, p.content)
}

/** The label that stands for a tab: tabs that group several labels use their first. */
fun InboxTab.accentLabel(): MessageLabel = when (this) {
    InboxTab.ALL -> MessageLabel.UNLABELED
    InboxTab.JUNK -> MessageLabel.SPAM
    else -> labels!!.first()
}

@Composable
fun tabStyle(tab: InboxTab): LabelStyle {
    val base = styleFor(tab.accentLabel())
    return when (tab) {
        InboxTab.ALL -> LabelStyle(
            tab.title, Icons.Filled.Inbox,
            MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
        )
        InboxTab.JUNK -> base.copy(displayName = tab.title, icon = Icons.Filled.Report)
        else -> base.copy(displayName = tab.title)
    }
}
