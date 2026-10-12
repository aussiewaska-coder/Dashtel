package dev.dialer.voice.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.dialer.voice.model.createDialPlan
import dev.dialer.voice.model.cleanDialInput
import dev.dialer.voice.model.formatPhoneNumber
import dev.dialer.voice.data.DeviceContact
import dev.dialer.voice.twilio.RegistrationState

private data class DialKey(val label: String, val value: String, val sub: String = "")

private val dialKeys = listOf(
    listOf(DialKey("1", "1"), DialKey("2", "2", "ABC"), DialKey("3", "3", "DEF")),
    listOf(DialKey("4", "4", "GHI"), DialKey("5", "5", "JKL"), DialKey("6", "6", "MNO")),
    listOf(DialKey("7", "7", "PQRS"), DialKey("8", "8", "TUV"), DialKey("9", "9", "WXYZ")),
    listOf(DialKey("*", "*"), DialKey("0", "0", "+"), DialKey("#", "#")),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialerScreen(
    number: String,
    suggestions: List<DeviceContact>,
    callerId: String,
    registration: RegistrationState,
    permissionIssues: List<String>,
    showHeader: Boolean,
    onFixPermissions: () -> Unit,
    onNumberChange: (String) -> Unit,
    onPaste: () -> Unit,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onDial: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showHeader) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Dialler", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("New call", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RegistrationStatusChip(registration)
            }
        }
        if (permissionIssues.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        permissionIssues.first(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = onFixPermissions) { Text("Fix") }
                }
            }
        }
        OutlinedTextField(
            value = number,
            onValueChange = onNumberChange,
            placeholder = { Text("Enter a number") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
            shape = RoundedCornerShape(20.dp),
            leadingIcon = {
                IconButton(onClick = onPaste) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste number")
                }
            },
            trailingIcon = {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp).combinedClickable(
                        enabled = number.isNotEmpty(),
                        role = Role.Button,
                        onClick = onBackspace,
                        onLongClick = onClear,
                    ),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete digit; hold to clear")
                }
            },
            supportingText = {
                val plan = createDialPlan(number)
                Text(
                    when {
                        number.isBlank() -> "Australia · Cambodia +855 · Thailand +66"
                        plan != null -> formatPhoneNumber(plan.destination) +
                            if (plan.postDialDigits.isNotBlank()) " · extension queued" else ""
                        else -> "Check this number"
                    },
                    color = if (number.isNotBlank() && plan == null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            isError = number.isNotBlank() && createDialPlan(number) == null,
        )
        if (suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                suggestions.take(2).forEach { contact ->
                    TextButton(
                        onClick = { onNumberChange(cleanDialInput(contact.number)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(contact.name, maxLines = 1)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Caller ID  ${callerId.ifBlank { "Not configured" }}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onNumberChange(number + ",") }, enabled = number.isNotBlank()) {
                Text("Add pause")
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            dialKeys.forEach { row ->
                Row(
                    modifier = Modifier.widthIn(max = 292.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    row.forEach { key ->
                        KeyButton(
                            key = key.label,
                            sub = key.sub,
                            onClick = { onDigit(key.value) },
                            onLongClick = if (key.value == "0") ({ onDigit("+") }) else null,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onDial,
            enabled = createDialPlan(number) != null,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.size(width = 154.dp, height = 58.dp),
        ) {
            Icon(Icons.Filled.Call, contentDescription = null)
            Text("Call", modifier = Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyButton(key: String, sub: String, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(68.dp).combinedClickable(
            role = Role.Button,
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(key, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun RegistrationStatusChip(registration: RegistrationState) {
    val (label, container, foreground) = when (registration) {
        is RegistrationState.Idle -> Triple("Setup needed", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        is RegistrationState.Registering -> Triple("Connecting…", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.tertiary)
        is RegistrationState.Ready -> Triple("Ready", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        is RegistrationState.Failed -> Triple("Unavailable", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
    }
    Surface(shape = CircleShape, color = container) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = foreground, style = MaterialTheme.typography.labelLarge)
    }
}
