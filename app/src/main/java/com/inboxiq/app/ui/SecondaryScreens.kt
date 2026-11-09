package com.inboxiq.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.inboxiq.app.data.BlockedNumberEntity

@Composable
fun NewConversationScreen(
    onBack: () -> Unit,
    onPickContact: ((String?) -> Unit) -> Unit,
    onStart: (String) -> Unit,
    modifier: Modifier = Modifier,
    forwardingText: String? = null,
) {
    var number by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to inbox") }
            Text(if (forwardingText != null) "Forward to" else "New message", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(16.dp)) {
            forwardingText?.let {
                Text("Forwarding:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(it, maxLines = 3, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 16.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("Phone number") },
                    supportingText = { Text("Include the area code. Add +1 and the country code for numbers abroad.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                IconButton(onClick = { onPickContact { picked -> if (picked != null) number = picked } }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Contacts, contentDescription = "Pick from contacts")
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { if (number.isNotBlank()) onStart(number.trim()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = number.isNotBlank()) {
                Text(if (forwardingText != null) "Continue" else "Start conversation")
            }
        }
    }
}

@Composable
fun BlockedNumbersScreen(blocked: List<BlockedNumberEntity>, nameOf: (String) -> String?, onUnblock: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings") }
            Text("Blocked numbers", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (blocked.isEmpty()) {
            StateMessage(
                Icons.Filled.Block, "No blocked numbers",
                "Block a number from a conversation's menu. Texts from blocked numbers are not saved or shown, and you can unblock them here.",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.navigationBarsPadding()) {
                items(blocked, key = { it.address }) { b ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(nameOf(b.address) ?: b.address, style = MaterialTheme.typography.titleSmall)
                            if (nameOf(b.address) != null) Text(b.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onUnblock(b.address) }) { Text("Unblock") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
