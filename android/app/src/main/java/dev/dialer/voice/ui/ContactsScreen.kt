package dev.dialer.voice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dialer.voice.data.DeviceContact
import dev.dialer.voice.model.formatPhoneNumber

@Composable
fun ContactsScreen(
    contacts: List<DeviceContact>,
    permissionGranted: Boolean,
    loading: Boolean,
    error: String?,
    compact: Boolean,
    onRequestPermission: () -> Unit,
    onPopulate: (String) -> Unit,
    onCall: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(contacts, query) { contacts.filter { it.matches(query) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(
            "Contacts",
            style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
        )
        Text(
            "Device contacts stay on this device",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!permissionGranted) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.Contacts, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Allow contact access to search names, numbers and T9 matches.")
                    Button(onClick = onRequestPermission) { Text("Allow contacts") }
                }
            }
            return
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            placeholder = { Text("Name, company, number or T9") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
        )
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            matches.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (contacts.isEmpty()) "No contacts found" else "No matching contacts")
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(matches, key = { "${it.id}:${it.number}" }) { contact ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.padding(11.dp), contentAlignment = Alignment.Center) {
                                Text(contact.initials, fontWeight = FontWeight.Bold)
                            }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${formatPhoneNumber(contact.number)} · ${contact.type}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onPopulate(contact.number) }) {
                            Icon(Icons.Default.Contacts, contentDescription = "Put number in dialler")
                        }
                        IconButton(onClick = { onCall(contact.number) }) {
                            Icon(Icons.Default.Call, contentDescription = "Call ${contact.name}")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
