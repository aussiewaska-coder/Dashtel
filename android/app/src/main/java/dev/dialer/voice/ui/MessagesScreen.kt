package dev.dialer.voice.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.dialer.voice.data.DeviceContact
import dev.dialer.voice.data.SmsManager
import dev.dialer.voice.model.formatPhoneNumber
import dev.dialer.voice.model.normalizeSmsRecipient
import dev.dialer.voice.model.smsSegments
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun MessagesScreen(
    contacts: List<DeviceContact>,
    modifier: Modifier = Modifier,
    externalPeer: String? = null,
    onExternalPeerConsumed: () -> Unit = {},
) {
    val state by SmsManager.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var peer by rememberSaveable(state.line) { mutableStateOf<String?>(null) }
    var composing by rememberSaveable(state.line) { mutableStateOf(false) }
    var recipient by rememberSaveable(state.line) { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    val draftKey = peer ?: "new"
    var draft by remember(state.line, draftKey) { mutableStateOf(SmsManager.draft(draftKey)) }
    val contactNames = remember(contacts) { contacts.mapNotNull { contact -> normalizeSmsRecipient(contact.number)?.let { it to contact.name } }.toMap() }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    fun closeThread() { peer = null; composing = false; SmsManager.clearError() }
    BackHandler(peer != null || composing) { closeThread() }
    LaunchedEffect(externalPeer) {
        externalPeer?.let { peer = it; composing = false; onExternalPeerConsumed() }
    }
    LaunchedEffect(lifecycle, state.line) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { SmsManager.refresh(); delay(15_000) }
        }
    }
    LaunchedEffect(lifecycle, peer, state.line) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            SmsManager.setVisiblePeer(peer)
            try { kotlinx.coroutines.awaitCancellation() } finally { SmsManager.setVisiblePeer(null) }
        }
    }
    DisposableEffect(Unit) { onDispose { SmsManager.setVisiblePeer(null) } }

    Column(modifier.fillMaxSize().imePadding().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (peer != null || composing) IconButton(onClick = { closeThread() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Conversations")
            }
            Column(Modifier.weight(1f)) {
                Text(peer?.let { contactNames[it] ?: formatPhoneNumber(it) } ?: if (composing) "New message" else "Messages",
                    style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(peer?.takeIf { contactNames.containsKey(it) } ?: "SMS · ${formatPhoneNumber(state.line)}",
                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            IconButton(onClick = { SmsManager.refresh() }, enabled = !state.loading) { Icon(Icons.Default.Refresh, "Refresh messages") }
            if (peer == null && !composing) IconButton(onClick = { composing = true; recipient = "" }) { Icon(Icons.Default.Add, "New message") }
        }
        if (state.loading || state.settingUp) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.error != null) {
            Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        }
        if (!state.pushEnabled && peer == null && !composing) {
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Background message alerts", style = MaterialTheme.typography.titleSmall)
                    Text("Enable after the SMS relay is deployed. Messages can still be refreshed here.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = SmsManager::enablePush, enabled = !state.settingUp && state.line.isNotBlank()) { Text("Enable notifications") }
                }
            }
        }
        if (peer == null && !composing) {
            OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                label = { Text("Search conversations") }, modifier = Modifier.fillMaxWidth())
            val threads = remember(state.messages, state.line, search, contactNames) {
                state.messages.groupBy { it.peer(state.line) }.entries.sortedByDescending { it.value.last().timestamp }
                    .filter { (address, messages) -> search.isBlank() || address.contains(search, true) ||
                        contactNames[address].orEmpty().contains(search, true) || messages.any { it.body.contains(search, true) } }
            }
            LazyColumn(Modifier.weight(1f)) {
                if (threads.isEmpty()) item {
                    Text(if (state.loading) "Loading messages…" else if (search.isNotBlank()) "No matching conversations" else "No messages yet. Tap + to start a conversation.", modifier = Modifier.padding(vertical = 24.dp))
                }
                items(threads, key = { it.key }) { (address, messages) ->
                    val last = messages.last()
                    val unread = messages.count { it.sid in state.unread }
                    Column(Modifier.fillMaxWidth().clickable { peer = address }.padding(vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(contactNames[address] ?: formatPhoneNumber(address), modifier = Modifier.weight(1f),
                                fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (unread > 0) Badge { Text(unread.toString()) }
                        }
                        Text((if (!last.incoming(state.line)) "You: " else "") + last.body.ifEmpty { "Media message" },
                            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(dateFormat.format(Date(last.timestamp)), style = MaterialTheme.typography.labelSmall)
                    }
                    HorizontalDivider()
                }
                if (state.canLoadOlder) item { TextButton(onClick = { SmsManager.refresh(older = true) }, enabled = !state.loading) { Text("Load older messages") } }
            }
        } else {
            if (composing) {
                OutlinedTextField(recipient, { recipient = it }, label = { Text("To · +61, +855 or +66") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(), enabled = !state.sending)
                if (recipient.length >= 2) {
                    contacts.filter { it.matches(recipient) }.take(3).forEach { contact ->
                        TextButton(onClick = { recipient = normalizeSmsRecipient(contact.number) ?: contact.number }, enabled = !state.sending) {
                            Text("${contact.name} · ${formatPhoneNumber(contact.number)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            val messages = state.messages.filter { it.peer(state.line) == peer }
            val listState = rememberLazyListState()
            // Newest first in the layout keeps incoming messages visible without jumping when older pages load.
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, reverseLayout = true,
                contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages.asReversed(), key = { it.sid }) { message ->
                    val incoming = message.incoming(state.line)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End) {
                        Surface(color = if (incoming) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(0.9f)) {
                            Column(Modifier.padding(12.dp)) {
                                androidx.compose.foundation.text.selection.SelectionContainer { Text(message.body.ifEmpty { "Media message" }) }
                                if (message.preview) Text("Refresh to load the full message", style = MaterialTheme.typography.labelSmall)
                                if (message.mediaCount > 0) Text("${message.mediaCount} attachment(s) · media viewing isn't available", style = MaterialTheme.typography.labelSmall)
                                Text(buildString {
                                    append(dateFormat.format(Date(message.timestamp)))
                                    if (!incoming) append(" · ").append(message.status)
                                    message.errorCode?.let { append(" · error ").append(it) }
                                }, style = MaterialTheme.typography.labelSmall,
                                    color = if (message.status in listOf("failed", "undelivered")) MaterialTheme.colorScheme.error else LocalContentColor.current)
                            }
                        }
                    }
                }
                if (state.canLoadOlder) item { TextButton(onClick = { SmsManager.refresh(older = true) }, enabled = !state.loading) { Text("Load older messages") } }
            }
            val target = peer ?: normalizeSmsRecipient(recipient)
            val canReply = target != null && normalizeSmsRecipient(target) != null
            if (!canReply && peer != null) Text("This sender cannot receive replies from this app.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(draft, { value ->
                    if (value.length <= 1600) { draft = value; SmsManager.saveDraft(draftKey, value) }
                }, label = { Text("Text message") }, modifier = Modifier.weight(1f), maxLines = 5, enabled = !state.sending,
                    supportingText = { Text("${draft.length}/1600 · ${smsSegments(draft)} SMS part(s)") })
                IconButton(enabled = canReply && draft.isNotBlank() && !state.sending,
                    onClick = {
                        val destination = target ?: return@IconButton
                        SmsManager.send(destination, draft) {
                            SmsManager.saveDraft(draftKey, "")
                            draft = ""; peer = destination; composing = false
                        }
                    }) {
                    if (state.sending) CircularProgressIndicator(Modifier.size(24.dp))
                    else Icon(Icons.AutoMirrored.Filled.Send, "Send SMS")
                }
            }
        }
    }
}
