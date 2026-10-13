package com.inboxiq.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.inboxiq.app.data.MessageLabel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun formatTimestamp(millis: Long, now: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
    val n = Calendar.getInstance().apply { timeInMillis = now }
    val t = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = n.get(Calendar.YEAR) == t.get(Calendar.YEAR) && n.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)
    val sameYear = n.get(Calendar.YEAR) == t.get(Calendar.YEAR)
    val pattern = when {
        sameDay -> android.text.format.DateFormat.getBestDateTimePattern(locale, "jmm")
        sameYear -> android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
        else -> android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMd")
    }
    return SimpleDateFormat(pattern, locale).format(Date(millis))
}

/** Time of day only. Inside a conversation the day headers already say which day. */
fun formatMessageTime(millis: Long, locale: Locale = Locale.getDefault()): String =
    SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "jmm"), locale).format(Date(millis))

fun formatFullTimestamp(millis: Long, locale: Locale = Locale.getDefault()): String =
    SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMdEjmmss"), locale).format(Date(millis))

/** Label name + icon + tint. Text carries the meaning; colour is reinforcement. */
@Composable
fun LabelChip(label: MessageLabel, modifier: Modifier = Modifier) {
    val style = styleFor(label)
    Surface(color = style.container, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(style.icon, contentDescription = null, tint = style.content, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(style.displayName, color = style.content, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun ContactAvatar(name: String?, address: String, label: MessageLabel, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val initial = name?.trim()?.firstOrNull { it.isLetter() }?.uppercaseChar()
    val style = styleFor(label)
    // A saved contact gets their initial; a number we don't know gets its label icon, so a bank's
    // short code reads as "Code" or "Promo" at a glance.
    Box(
        modifier = Modifier
            .size(size)
            .background(if (initial != null) MaterialTheme.colorScheme.primaryContainer else style.container, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (initial != null) {
            Text(initial.toString(), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
        } else {
            Icon(style.icon, contentDescription = null, tint = style.content, modifier = Modifier.size(size / 2))
        }
    }
}

/** Empty, loading and error states all use this: say what is going on, then what to do about it. */
@Composable
fun StateMessage(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
