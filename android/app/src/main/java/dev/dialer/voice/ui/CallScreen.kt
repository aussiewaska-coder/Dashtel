package dev.dialer.voice.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dialer.voice.call.AudioRouter
import dev.dialer.voice.model.CallState
import dev.dialer.voice.model.formatPhoneNumber
import dev.dialer.voice.twilio.ActiveCall
import kotlinx.coroutines.delay

@Composable
fun CallScreen(
    call: ActiveCall,
    contactName: String?,
    audioRoute: String,
    availableRoutes: List<AudioRouter.Route>,
    compact: Boolean,
    onMute: () -> Unit,
    onHold: () -> Unit,
    onSpeaker: () -> Unit,
    onSelectAudioRoute: (String) -> Unit,
    onDtmf: (String) -> Unit,
    onHangup: () -> Unit,
    onDismiss: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onKeypad: () -> Unit,
    keypadVisible: Boolean,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var routeMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(call.connectedAtEpochMillis, call.held) {
        while (call.connectedAtEpochMillis != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val connectedDuration = call.connectedAtEpochMillis?.let { connectedAt ->
        val currentHold = call.holdStartedAtEpochMillis?.let { (now - it).coerceAtLeast(0) } ?: 0
        (now - connectedAt - call.totalHeldMillis - currentHold).coerceAtLeast(0)
    }
    val status = when (call.state) {
        CallState.Connecting -> "Dialling…"
        CallState.Ringing -> "Ringing…"
        CallState.Incoming -> "Incoming call"
        CallState.Connected -> if (call.held) "On hold" else formatDuration(connectedDuration ?: 0)
        CallState.Reconnecting -> "Connection interrupted · reconnecting…"
        CallState.Disconnecting -> "Ending call…"
        CallState.Failed -> call.errorMessage ?: "Call failed"
        CallState.Idle -> ""
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(if (compact) 20.dp else 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (call.inbound) "Incoming" else "Active call",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(if (compact) 80.dp else 64.dp), contentAlignment = Alignment.Center) {
                Text(
                    contactName?.split(Regex("\\s+"))?.take(2)?.mapNotNull { it.firstOrNull() }
                        ?.joinToString("")?.uppercase()
                        ?: call.number.filter(Char::isDigit).takeLast(2).ifBlank { "☎" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            contactName ?: formatPhoneNumber(call.number.ifBlank { "Unknown" }),
            style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (contactName != null) {
            Text(formatPhoneNumber(call.number), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            status,
            style = MaterialTheme.typography.titleMedium,
            color = when (call.state) {
                CallState.Failed, CallState.Reconnecting -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (call.state == CallState.Reconnecting) {
            Text(
                "Keeping this call alive — do not redial",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (call.networkLabel.isNotBlank() && call.isLive) {
            Text(call.networkLabel, style = MaterialTheme.typography.labelSmall)
        }
        if (call.qualityWarnings.isNotEmpty()) {
            Text(
                "Poor connection: ${call.qualityWarnings.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (call.held && call.holdStartedAtEpochMillis != null) {
            Text(
                "Hold ${formatDuration(now - call.holdStartedAtEpochMillis)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (call.state == CallState.Failed) {
            call.errorCode?.let { Text("Twilio Voice code ${it}", style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = onCopyDiagnostics) { Text("Copy diagnostics") }
        }
        Spacer(Modifier.weight(1f))
        if (keypadVisible) {
            DtmfPad(onDtmf)
            Spacer(Modifier.height(12.dp))
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CallControl("Mute", Icons.Default.MicOff, call.muted, onMute, call.state == CallState.Connected)
                CallControl("Keypad", Icons.Default.Dialpad, false, onKeypad, call.state == CallState.Connected)
                CallControl("Speaker", Icons.AutoMirrored.Filled.VolumeUp, call.speaker, onSpeaker, call.isLive)
                CallControl("Hold", Icons.Outlined.Pause, call.held, onHold, call.state == CallState.Connected)
            }
        }
        Spacer(Modifier.height(16.dp))
        Box {
            OutlinedButton(onClick = { routeMenuOpen = true }, enabled = availableRoutes.isNotEmpty()) {
                Icon(Icons.Default.BluetoothAudio, contentDescription = null)
                Text(audioRoute, modifier = Modifier.padding(start = 8.dp))
            }
            DropdownMenu(expanded = routeMenuOpen, onDismissRequest = { routeMenuOpen = false }) {
                availableRoutes.forEach { route ->
                    DropdownMenuItem(
                        text = { Text(route.label) },
                        onClick = {
                            routeMenuOpen = false
                            onSelectAudioRoute(route.id)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (keypadVisible) {
                OutlinedButton(onClick = onKeypad) { Text("Close keypad") }
            } else if (compact) {
                OutlinedButton(onClick = onDismiss, enabled = call.isLive) { Text("Hide") }
            }
            Surface(
                onClick = onHangup,
                enabled = call.isLive,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(72.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.CallEnd, contentDescription = "End call", tint = Color.White)
                }
            }
            if (!call.isLive) {
                Button(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun CallControl(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun DtmfPad(onDtmf: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        listOf("123", "456", "789", "*0#").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                row.forEach { digit ->
                    Surface(onClick = { onDtmf(digit.toString()) }, shape = CircleShape, tonalElevation = 2.dp) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                            Text(digit.toString(), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
