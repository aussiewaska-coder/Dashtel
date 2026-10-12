package dev.dialer.voice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dialer.voice.data.AppConfig
import dev.dialer.voice.data.ThemeMode
import dev.dialer.voice.twilio.RegistrationState

@Composable
fun SettingsScreen(
    config: AppConfig,
    registration: RegistrationState,
    busy: Boolean,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onClose: () -> Unit,
    onSave: (AppConfig) -> Unit,
    onTest: (AppConfig) -> Unit,
    onCopyDiagnostics: () -> Unit,
    diagnostics: List<String>,
    testResult: String?,
    testCallCountdownSeconds: Int?,
    onScheduleTestCall: (AppConfig) -> Unit,
) {
    var draft by remember(config) { mutableStateOf(config) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
                Text("DashPhone", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close settings")
            }
        }
        HorizontalDivider()
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection("Account & phone number") {
                Text(
                    "Destinations: Australia (+61), Cambodia (+855) and Thailand (+66). Australian national format is the default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Credentials are encrypted with this device's Android Keystore and are never included in the APK.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Field("Account SID", draft.accountSid, "AC…") { draft = draft.copy(accountSid = it.trim()) }
                Field("API key SID", draft.apiKeySid, "SK…") { draft = draft.copy(apiKeySid = it.trim()) }
                Field("API key secret", draft.apiKeySecret, "Secret", secret = true) {
                    draft = draft.copy(apiKeySecret = it)
                }
                Field("TwiML App SID", draft.twimlAppSid, "AP…") { draft = draft.copy(twimlAppSid = it.trim()) }
                Field("Outbound caller ID", draft.phoneNumber, "+61…", phone = true) {
                    draft = draft.copy(phoneNumber = it.trim())
                }
                Field("Twilio identity", draft.identity, "default") { draft = draft.copy(identity = it.trim()) }
            }

            SettingsSection("Incoming calls") {
                Field("Push Credential SID", draft.pushCredentialSid, "CR…") {
                    draft = draft.copy(pushCredentialSid = it.trim())
                }
                Text(
                    "This identity and the deployed Twilio Function must match. Android notification, microphone and Bluetooth permissions are managed from the main workspace.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection("Appearance") {
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            label = { Text(mode.name) },
                        )
                    }
                }
                Text(
                    "DashPhone adapts panes to the live window width for phones, tablets, DeX and free-form windows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection("Connection & diagnostics") {
                val registrationText = when (registration) {
                    is RegistrationState.Ready -> "Registered as ${registration.identity}"
                    is RegistrationState.Registering -> "Registering with Twilio…"
                    is RegistrationState.Failed -> "Registration failed: ${registration.message}"
                    RegistrationState.Idle -> "Not configured"
                }
                Text(
                    registrationText,
                    color = if (registration is RegistrationState.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onSave(draft) }, enabled = !busy) { Text("Save") }
                    OutlinedButton(onClick = { onTest(draft) }, enabled = !busy) { Text("Test connection") }
                }
                Button(
                    onClick = { onScheduleTestCall(draft) },
                    enabled = !busy && draft.canMintToken && draft.hasPushCredential,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        testCallCountdownSeconds?.let { seconds ->
                            if (seconds > 0) "Incoming test call in ${seconds}s…" else "Sending test call…"
                        } ?: "Test locked-screen call in 10 seconds",
                    )
                }
                Text(
                    "Saves these settings, then rings this DashPhone identity through Twilio. Press it and lock the phone before the countdown ends. This may incur a Twilio call charge.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy && testCallCountdownSeconds == null) {
                    Text("Checking configuration…", style = MaterialTheme.typography.bodySmall)
                }
                testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = onCopyDiagnostics, enabled = diagnostics.isNotEmpty()) {
                    Text("Copy redacted diagnostics")
                }
                diagnostics.takeLast(8).asReversed().forEach { entry ->
                    Text(
                        entry,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    secret: Boolean = false,
    phone: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                secret -> KeyboardType.Password
                phone -> KeyboardType.Phone
                else -> KeyboardType.Text
            },
        ),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}
