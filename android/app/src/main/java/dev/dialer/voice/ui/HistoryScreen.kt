package dev.dialer.voice.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.PhoneMissed
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dialer.voice.data.DeviceContact
import dev.dialer.voice.model.CallRecord
import dev.dialer.voice.model.formatPhoneNumber
import dev.dialer.voice.model.normalizeForDial
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

private enum class HistoryFilter(val label: String) { All("All"), Missed("Missed"), Incoming("Incoming"), Outgoing("Outgoing") }

@Composable
fun HistoryScreen(
    calls: List<CallRecord>,
    contacts: List<DeviceContact>,
    loading: Boolean,
    error: String?,
    compact: Boolean,
    onRefresh: () -> Unit,
    onPopulate: (String) -> Unit,
    onCall: (String) -> Unit,
    onCopy: (String) -> Unit,
    onPlay: (CallRecord) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(HistoryFilter.All) }
    val contactByNumber = remember(contacts) {
        contacts.mapNotNull { contact -> normalizeForDial(contact.number)?.let { it to contact } }.toMap()
    }
    val filtered = remember(calls, query, filter, contactByNumber) {
        calls.filter { record ->
            val number = record.displayNumber
            val name = normalizeForDial(number)?.let(contactByNumber::get)?.name.orEmpty()
            val queryMatch = query.isBlank() || number.contains(query, true) || name.contains(query, true)
            val filterMatch = when (filter) {
                HistoryFilter.All -> true
                HistoryFilter.Missed -> record.isMissed
                HistoryFilter.Incoming -> record.isInbound
                HistoryFilter.Outgoing -> !record.isInbound
            }
            queryMatch && filterMatch
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Recents", style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
                Text("${calls.size} synced calls", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRefresh, enabled = !loading) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh call history")
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            placeholder = { Text("Search names or numbers") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
        ) {
            HistoryFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option.label) },
                )
            }
        }
        if (loading && calls.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (error != null && calls.isEmpty()) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        } else if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (calls.isEmpty()) "No calls yet" else "No matching calls", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.sid }) { record ->
                    val number = record.displayNumber
                    val contact = normalizeForDial(number)?.let(contactByNumber::get)
                    CallRow(
                        record = record,
                        number = number,
                        name = contact?.name,
                        onPopulate = { onPopulate(number) },
                        onCall = { onCall(number) },
                        onCopy = { onCopy(number) },
                        onPlay = { onPlay(record) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallRow(
    record: CallRecord,
    number: String,
    name: String?,
    onPopulate: () -> Unit,
    onCall: () -> Unit,
    onCopy: () -> Unit,
    onPlay: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val (icon, tint) = record.iconAndTint
    Row(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onPopulate, onLongClick = { menuOpen = true })
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = tint.copy(alpha = 0.14f)) {
            Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = record.outcomeLabel, tint = tint)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                name ?: formatPhoneNumber(number),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (record.isMissed) FontWeight.Bold else FontWeight.Medium,
                color = if (record.isMissed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (name != null) Text(formatPhoneNumber(number), style = MaterialTheme.typography.labelMedium)
            Text(
                "${record.outcomeLabel} · ${record.timeLabel} · ${record.durationLabel}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (record.recordingSids.isNotEmpty()) {
            IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, contentDescription = "Play recording") }
        }
        IconButton(onClick = onCall) { Icon(Icons.Default.Call, contentDescription = "Call back") }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Call actions") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Call") },
                    leadingIcon = { Icon(Icons.Default.Call, contentDescription = null) },
                    onClick = { menuOpen = false; onCall() },
                )
                DropdownMenuItem(
                    text = { Text("Copy number") },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    onClick = { menuOpen = false; onCopy() },
                )
            }
        }
    }
}

private val CallRecord.displayNumber: String
    get() = (if (isInbound) from else to).ifBlank { "Unknown" }

private val CallRecord.isMissed: Boolean
    get() = isInbound && status.lowercase() in setOf("no-answer", "busy", "failed", "canceled")

private val CallRecord.outcomeLabel: String
    get() = when {
        isMissed -> "Missed"
        status.equals("failed", true) -> "Failed"
        isInbound -> "Incoming"
        else -> "Outgoing"
    }

private val CallRecord.timeLabel: String
    get() {
        val raw = startedAt ?: return "Unknown time"
        val parsed = sequenceOf(
            "yyyy-MM-dd'T'HH:mm:ssX",
            "EEE, dd MMM yyyy HH:mm:ss Z",
        ).mapNotNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.US).parse(raw) }.getOrNull()
        }.firstOrNull() ?: return raw
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(parsed)
    }

private val CallRecord.iconAndTint: Pair<ImageVector, androidx.compose.ui.graphics.Color>
    @Composable get() = when {
        isMissed -> Icons.AutoMirrored.Filled.PhoneMissed to MaterialTheme.colorScheme.error
        isInbound -> Icons.AutoMirrored.Filled.CallReceived to MaterialTheme.colorScheme.primary
        else -> Icons.AutoMirrored.Filled.CallMade to MaterialTheme.colorScheme.secondary
    }
