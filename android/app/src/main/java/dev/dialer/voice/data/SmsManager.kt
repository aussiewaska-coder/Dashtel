package dev.dialer.voice.data

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import dev.dialer.voice.model.SmsMessage
import dev.dialer.voice.model.normalizeSmsRecipient
import dev.dialer.voice.notifications.SmsNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SmsState(
    val line: String = "",
    val messages: List<SmsMessage> = emptyList(),
    val unread: Set<String> = emptySet(),
    val loading: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
    val canLoadOlder: Boolean = false,
    val pushEnabled: Boolean = false,
    val settingUp: Boolean = false,
)

object SmsManager {
    private lateinit var context: Context
    private lateinit var store: SmsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(SmsState())
    val state = mutable.asStateFlow()
    private var config = AppConfig()
    private var key = ""
    private var generation = 0
    private var visiblePeer: String? = null
    private var nextIn: String? = null
    private var nextOut: String? = null
    private var pagesLoaded = false
    private val prefs get() = context.getSharedPreferences("sms_push", Context.MODE_PRIVATE)

    fun init(context: Context) {
        this.context = context.applicationContext
        store = SmsStore(this.context)
        SmsNotifier.init(this.context)
        configure(ConfigStore.load())
    }

    @Synchronized
    fun configure(value: AppConfig) {
        if (value == config && key.isNotEmpty()) return
        val newKey = MessageDigest.getInstance("SHA-256")
            .digest("${value.accountSid.trim()}:${value.phoneNumber.trim()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (key.isNotEmpty() && key != newKey) SmsNotifier.clear(context)
        config = value
        key = newKey
        generation++
        visiblePeer = null
        nextIn = null; nextOut = null; pagesLoaded = false
        mutable.value = SmsState(line = value.phoneNumber.trim(), messages = store.messages(key), unread = store.unread(key), pushEnabled = prefs.getBoolean(newKey, false))
        onNewToken()
    }

    fun onNewToken() {
        if (!::context.isInitialized) return
        if (mutable.value.pushEnabled) scope.launch { enablePush() }
    }

    @Synchronized
    private fun publish() {
        mutable.update { it.copy(messages = store.messages(key), unread = store.unread(key)) }
    }

    @Synchronized
    fun setVisiblePeer(peer: String?) {
        visiblePeer = peer
        if (peer != null) {
            store.markRead(key, peer)
            SmsNotifier.cancel(context, peer)
            publish()
        }
    }

    fun draft(peer: String) = store.draft(key, peer)
    fun saveDraft(peer: String, text: String) = store.saveDraft(key, peer, text)
    fun clearError() { mutable.update { it.copy(error = null) } }

    fun refresh(older: Boolean = false) {
        if (mutable.value.loading || !config.isConfigured || (older && !mutable.value.canLoadOlder)) return
        val current = config; val epoch = generation
        mutable.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val incoming = if (!older || nextIn != null) TwilioRest.listMessages(current, true, if (older) nextIn else null) else null
                val outgoing = if (!older || nextOut != null) TwilioRest.listMessages(current, false, if (older) nextOut else null) else null
                if (epoch != generation) return@launch
                store.merge(key, current.phoneNumber.trim(), incoming?.messages.orEmpty() + outgoing?.messages.orEmpty(), visiblePeer)
                if (older || !pagesLoaded) {
                    nextIn = incoming?.next; nextOut = outgoing?.next; pagesLoaded = true
                }
                publish()
                mutable.update { it.copy(canLoadOlder = nextIn != null || nextOut != null) }
            } catch (error: Exception) {
                if (epoch == generation) mutable.update { it.copy(error = error.message ?: "Could not refresh messages") }
            } finally {
                if (epoch == generation) mutable.update { it.copy(loading = false) }
            }
        }
    }

    fun send(peer: String, body: String, onSent: () -> Unit) {
        if (mutable.value.sending) return
        val destination = normalizeSmsRecipient(peer)
        if (destination == null || body.isBlank() || body.length > 1600 || !config.isConfigured) {
            mutable.update { it.copy(error = "Check the Twilio settings, recipient and message (maximum 1,600 characters).") }
            return
        }
        val current = config; val epoch = generation
        mutable.update { it.copy(sending = true, error = null) }
        scope.launch {
            try {
                TwilioRest.smsLine(current)
                val message = TwilioRest.sendMessage(current, destination, body)
                if (epoch != generation) return@launch
                store.merge(key, current.phoneNumber.trim(), listOf(message), visiblePeer)
                store.saveDraft(key, peer, "")
                publish()
                onSent()
            } catch (error: Exception) {
                if (epoch == generation) mutable.update { it.copy(error =
                    if (error is TwilioRest.TwilioApiError || error is IllegalArgumentException) error.message
                    else "Could not confirm sending. Refresh this conversation before trying again to avoid a duplicate SMS.") }
            } finally {
                if (epoch == generation) mutable.update { it.copy(sending = false) }
            }
        }
    }

    fun enablePush() {
        if (mutable.value.settingUp || !config.isConfigured) return
        val current = config; val epoch = generation
        mutable.update { it.copy(settingUp = true, error = null) }
        scope.launch {
            try {
                val token = deviceToken()
                if (epoch != generation) return@launch
                TwilioRest.enableSmsPush(current, token)
                if (epoch != generation) return@launch
                prefs.edit().putBoolean(key, true).apply()
                mutable.update { it.copy(pushEnabled = true) }
            } catch (error: Exception) {
                if (epoch == generation) mutable.update { it.copy(error = error.message ?: "Could not enable message notifications") }
            } finally {
                if (epoch == generation) mutable.update { it.copy(settingUp = false) }
            }
        }
    }

    private suspend fun deviceToken(): String = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
            .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    }

    /** Persist before returning to Firebase so process death cannot lose the notification. */
    @Synchronized
    fun receivePush(data: Map<String, String>) {
        val current = ConfigStore.load()
        if (current != config) configure(current)
        if (data["accountSid"] != current.accountSid.trim() || data["to"] != current.phoneNumber.trim()) return
        val sid = data["messageSid"] ?: return
        if (!Regex("^[SM]M[a-fA-F0-9]{32}$").matches(sid)) return
        val from = data["sender"]?.takeIf { it.isNotBlank() } ?: return
        val message = SmsMessage(sid, from, current.phoneNumber.trim(), data["body"].orEmpty(), "received",
            data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis(), mediaCount = data["mediaCount"]?.toIntOrNull() ?: 0, preview = true)
        val notify = store.merge(key, current.phoneNumber.trim(), listOf(message), visiblePeer)
        publish()
        if (notify.isNotEmpty()) SmsNotifier.show(context, from, mutable.value.messages.filter { it.from == from && it.sid in mutable.value.unread })
    }
}
