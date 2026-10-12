package dev.dialer.voice.twilio

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.UUID
import dev.dialer.voice.model.createDialPlan
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.twilio.voice.AcceptOptions
import com.twilio.voice.Call
import com.twilio.voice.CallException
import com.twilio.voice.CallInvite
import com.twilio.voice.CancelledCallInvite
import com.twilio.voice.ConnectOptions
import com.twilio.voice.MessageListener
import com.twilio.voice.RegistrationException
import com.twilio.voice.RegistrationListener
import com.twilio.voice.Voice
import dev.dialer.voice.call.AudioRouter
import dev.dialer.voice.call.CallForegroundService
import dev.dialer.voice.data.AppConfig
import dev.dialer.voice.data.ConfigStore
import dev.dialer.voice.data.TwilioJwt
import dev.dialer.voice.diagnostics.DiagnosticLog
import dev.dialer.voice.model.CallState
import dev.dialer.voice.network.NetworkMonitor
import dev.dialer.voice.notifications.Notifier
import dev.dialer.voice.telecom.TelecomCallManager
import android.telecom.DisconnectCause
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface RegistrationState {
    data object Idle : RegistrationState
    data object Registering : RegistrationState
    data class Ready(val identity: String) : RegistrationState
    data class Failed(val message: String) : RegistrationState
}

data class ActiveCall(
    val state: CallState = CallState.Idle,
    val sessionId: String? = null,
    val number: String = "",
    val inbound: Boolean = false,
    val sid: String? = null,
    val errorMessage: String? = null,
    val errorCode: Int? = null,
    val muted: Boolean = false,
    val held: Boolean = false,
    val speaker: Boolean = false,
    val startedAtEpochMillis: Long? = null,
    val connectedAtEpochMillis: Long? = null,
    val holdStartedAtEpochMillis: Long? = null,
    val totalHeldMillis: Long = 0L,
    val reconnectStartedAtEpochMillis: Long? = null,
    val networkLabel: String = "",
    val qualityWarnings: Set<String> = emptySet(),
) {
    fun matchesIncoming(callSid: String): Boolean = state == CallState.Incoming && sid == callSid

    val isLive: Boolean
        get() = state in setOf(
            CallState.Connecting,
            CallState.Ringing,
            CallState.Incoming,
            CallState.Connected,
            CallState.Reconnecting,
            CallState.Disconnecting,
        )
}

object VoiceManager {

    private const val TAG = "VoiceManager"
    private const val REGISTRATION_PREFS = "voice-registration"
    private const val KEY_REGISTRATION_FINGERPRINT = "binding-fingerprint"
    private const val KEY_REGISTRATION_TIME = "registered-at"
    private const val CONFLICT_ERROR_CODE = 31409
    private const val MAX_CONFLICT_ATTEMPTS = 3
    private const val CONFLICT_RETRY_BASE_MILLIS = 1_500L
    private const val FCM_TOKEN_TIMEOUT_MILLIS = 30_000L
    private const val REGISTRATION_TIMEOUT_MILLIS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)

    private var appContext: Context? = null
    private var twilioCall: Call? = null
    private var registrationRunning = false
    private var registrationPending = false
    private var forceRegistrationPending = false
    private var postDialJob: Job? = null
    private var incomingExpiryJob: Job? = null
    private var pendingPostDialDigits: String = ""
    private var disconnectJob: Job? = null

    private val _registration = MutableStateFlow<RegistrationState>(RegistrationState.Idle)
    val registration: StateFlow<RegistrationState> = _registration.asStateFlow()

    private val _activeCall = MutableStateFlow(ActiveCall())
    val activeCall: StateFlow<ActiveCall> = _activeCall.asStateFlow()

    fun start(context: Context) {
        appContext = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var previousLabel: String? = null
            var previousNetworkValidated: Boolean? = null
            NetworkMonitor.status.collect { status ->
                val label = status.label
                if (_activeCall.value.isLive && label != previousLabel) {
                    DiagnosticLog.info("Call network", label)
                }
                if (status.validated && previousNetworkValidated == false &&
                    _registration.value is RegistrationState.Failed
                ) {
                    DiagnosticLog.info("Push registration", "Network restored; retrying registration")
                    resume()
                }
                previousLabel = label
                previousNetworkValidated = status.validated
                _activeCall.update { call ->
                    if (call.isLive) call.copy(networkLabel = label) else call
                }
            }
        }
        resume()
    }

    fun onNewFcmToken() {
        resume()
    }

    fun resume(force: Boolean = false) {
        // FirebaseMessagingService can invoke this from a worker thread. Keep all
        // coordinator state confined to the main dispatcher.
        scope.launch { scheduleRegistration(force) }
    }

    private fun scheduleRegistration(force: Boolean) {
        val config = ConfigStore.load()
        if (registrationRunning) {
            // Voice.register is callback based. Cancelling the coroutine that invoked it does
            // not cancel the Twilio request, so overlapping calls can produce error 31409.
            registrationPending = true
            forceRegistrationPending = forceRegistrationPending || force
            return
        }
        if (!config.isConfigured) {
            _registration.value = RegistrationState.Idle
            return
        }
        registrationRunning = true
        scope.launch {
            try {
                register(config, force)
            } finally {
                registrationRunning = false
                if (registrationPending) {
                    val pendingForce = forceRegistrationPending
                    registrationPending = false
                    forceRegistrationPending = false
                    scheduleRegistration(pendingForce)
                }
            }
        }
    }

    fun saveConfig(config: AppConfig) {
        ConfigStore.save(config)
        // Saving is also the user's explicit recovery path if a server-side binding
        // was removed while the local FCM token remained unchanged.
        resume(force = true)
    }

    /** Tokens expire; mint a fresh one for every dial and registration. */
    private fun freshToken(config: AppConfig): String = TwilioJwt.mint(
        config.accountSid,
        config.apiKeySid,
        config.apiKeySecret,
        config.twimlAppSid,
        config.identity,
        config.pushCredentialSid,
    )

    private suspend fun register(config: AppConfig, force: Boolean) {
        _registration.value = RegistrationState.Registering
        try {
            if (!config.hasPushCredential) {
                DiagnosticLog.warn("Push registration", "Not attempted: Push Credential SID is missing")
                _registration.value = RegistrationState.Failed("Add a Twilio Push Credential SID to receive calls")
                return
            }
            val accessToken = freshToken(config)
            val senderId = FirebaseApp.getInstance().options.gcmSenderId.orEmpty().trim()
            if (senderId.isEmpty()) {
                DiagnosticLog.warn("Push registration", "Not attempted: Firebase sender ID is missing")
                _registration.value = RegistrationState.Failed("Add this app's Firebase config to enable incoming calls")
                return
            }
            val fcmRegistrationToken = withTimeout(FCM_TOKEN_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    FirebaseMessaging.getInstance().token
                        .addOnCompleteListener { task ->
                            if (!continuation.isActive) return@addOnCompleteListener
                            if (task.isSuccessful) {
                                val token = task.result?.trim().orEmpty()
                                if (token.isNotEmpty()) continuation.resume(token)
                                else continuation.resumeWithException(
                                    IllegalStateException("Firebase returned an empty FCM device token"),
                                )
                            } else {
                                continuation.resumeWithException(
                                    task.exception ?: IllegalStateException("Could not retrieve the FCM device token"),
                                )
                            }
                        }
                }
            }
            val fingerprint = RegistrationPolicy.fingerprint(
                config = config,
                senderId = senderId,
                fcmToken = fcmRegistrationToken,
            )
            val context = appContext ?: error("DashPhone application context is unavailable")
            val preferences = context.getSharedPreferences(REGISTRATION_PREFS, Context.MODE_PRIVATE)
            val lastFingerprint = preferences.getString(KEY_REGISTRATION_FINGERPRINT, null)
            val lastRegisteredAt = preferences.getLong(KEY_REGISTRATION_TIME, 0L)
            if (!force && !RegistrationPolicy.needsRegistration(
                    previousFingerprint = lastFingerprint,
                    registeredAtMillis = lastRegisteredAt,
                    nowMillis = System.currentTimeMillis(),
                    currentFingerprint = fingerprint,
                )
            ) {
                DiagnosticLog.info("Push registration", "Existing device registration is current")
                _registration.value = RegistrationState.Ready(config.identity)
                return
            }

            var attempt = 0
            while (true) {
                attempt += 1
                try {
                    awaitRegistration(accessToken, fcmRegistrationToken)
                    preferences.edit()
                        .putString(KEY_REGISTRATION_FINGERPRINT, fingerprint)
                        .putLong(KEY_REGISTRATION_TIME, System.currentTimeMillis())
                        .apply()
                    Log.i(TAG, "registered identity=${config.identity}")
                    DiagnosticLog.info("Push registration", "Registered identity=${config.identity}")
                    _registration.value = RegistrationState.Ready(config.identity)
                    return
                } catch (error: RegistrationException) {
                    if (error.errorCode == CONFLICT_ERROR_CODE && attempt < MAX_CONFLICT_ATTEMPTS) {
                        val retryDelay = CONFLICT_RETRY_BASE_MILLIS * attempt
                        DiagnosticLog.warn(
                            "Push registration",
                            "Twilio registration was already in progress; retrying",
                        )
                        delay(retryDelay)
                        continue
                    }
                    throw error
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "register threw", error)
            val message = error.message ?: error.javaClass.simpleName
            val registrationError = error as? RegistrationException
            val code = registrationError?.errorCode?.toString() ?: error.javaClass.simpleName
            DiagnosticLog.error("Push registration", message, code)
            _registration.value = if (registrationError != null) {
                RegistrationState.Failed("Twilio code ${registrationError.errorCode}: $message")
            } else {
                RegistrationState.Failed(message)
            }
        }
    }

    private suspend fun awaitRegistration(accessToken: String, fcmToken: String) {
        withTimeout(REGISTRATION_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                Voice.register(
                    accessToken,
                    Voice.RegistrationChannel.FCM,
                    fcmToken,
                    object : RegistrationListener {
                        override fun onRegistered(deviceToken: String, identity: String) {
                            // SDK callback values may contain registration tokens. Never log them.
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onError(
                            error: RegistrationException,
                            deviceToken: String,
                            identity: String,
                        ) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    },
                )
            }
        }
    }

    /** Raw payload never enters diagnostics; only message type and scoped lifecycle events do. */
    fun handleVoiceMessage(context: Context, payload: Map<String, String>) {
        val handled = Voice.handleMessage(context, payload, object : MessageListener {
            override fun onCallInvite(invite: CallInvite) {
                main.post {
                    val active = _activeCall.value
                    if (active.isLive) {
                        // A retransmission must not reject the invite already being presented.
                        if (active.sid != invite.callSid) runCatching { invite.reject(context) }
                        return@post
                    }
                    InviteRegistry.put(invite)
                    _activeCall.value = ActiveCall(
                        state = CallState.Incoming,
                        sessionId = UUID.randomUUID().toString(),
                        sid = invite.callSid,
                        number = invite.from ?: "Unknown caller",
                        inbound = true,
                        startedAtEpochMillis = System.currentTimeMillis(),
                        networkLabel = NetworkMonitor.status.value.label,
                    )
                    DiagnosticLog.info("Incoming call", "Live Twilio invite received")
                    scheduleIncomingExpiry(invite.callSid)
                    if (syncCallService()) TelecomCallManager.submit(_activeCall.value)
                }
            }

            override fun onCancelledCallInvite(cancelled: CancelledCallInvite, error: CallException?) {
                main.post {
                    // Check identity AND phase before touching the current call's timer or UI.
                    if (!_activeCall.value.matchesIncoming(cancelled.callSid)) return@post
                    DiagnosticLog.info("Incoming call", "Caller canceled the current invite")
                    finishSession(DisconnectCause.CANCELED)
                }
            }
        })
        if (!handled) DiagnosticLog.warn("Incoming push", "Unrecognized Voice SDK message")
    }

    fun dial(number: String, postDialDigits: String = "") {
        val id = prepareOutgoing(number, postDialDigits) ?: return
        if (_activeCall.value.sessionId == id && syncCallService()) TelecomCallManager.submit(_activeCall.value)
    }

    internal fun prepareOutgoing(number: String, postDialDigits: String = ""): String? {
        if (_activeCall.value.isLive) return null
        val context = appContext ?: return null
        val plan = createDialPlan(number)
        val error = when {
            !ConfigStore.load().isConfigured -> "Add your Twilio credentials in Settings first"
            plan == null -> "Enter an Australian number, or use +855 / +66"
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                "Allow microphone access before calling"
            else -> null
        }
        if (error != null) {
            _activeCall.value = ActiveCall(state = CallState.Failed, number = number, errorMessage = error)
            return null
        }
        val id = UUID.randomUUID().toString()
        pendingPostDialDigits = postDialDigits.ifBlank { plan!!.postDialDigits }
        _activeCall.value = ActiveCall(
            state = CallState.Connecting,
            sessionId = id,
            number = plan!!.destination,
            startedAtEpochMillis = System.currentTimeMillis(),
            networkLabel = NetworkMonitor.status.value.label,
        )
        return id
    }

    internal fun onTelecomReady(id: String) {
        val active = _activeCall.value
        if (active.sessionId != id || !active.isLive) return
        if (!syncCallService() || active.inbound || twilioCall != null) return
        try {
            val config = ConfigStore.load()
            val options = ConnectOptions.Builder(freshToken(config))
                .params(mapOf("identity" to config.identity, "To" to active.number)).build()
            twilioCall = Voice.connect(appContext!!, options, listener(id))
            publishState()
        } catch (error: Exception) {
            finishSession(DisconnectCause.ERROR, "Could not connect: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    internal fun onTelecomFailure(id: String, message: String) {
        if (_activeCall.value.sessionId != id || !_activeCall.value.isLive) return
        DiagnosticLog.error("System calling", message)
        val inbound = _activeCall.value.inbound
        finishSession(DisconnectCause.ERROR, message)
        if (inbound) appContext?.let { Notifier.showCallFailure(it, message) }
    }

    fun acceptInvite(id: String) {
        val context = appContext ?: return
        val active = _activeCall.value
        if (active.sessionId != id || active.state != CallState.Incoming) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onTelecomFailure(id, "Allow microphone access in DashPhone before answering")
            return
        }
        val invite = active.sid?.let(InviteRegistry::take)
        if (invite == null) {
            onTelecomFailure(id, "This incoming call is no longer available")
            return
        }
        incomingExpiryJob?.cancel()
        incomingExpiryJob = null
        _activeCall.update { it.copy(state = CallState.Connecting) }
        try {
            twilioCall = invite.accept(context, AcceptOptions.Builder().build(), listener(id))
            publishState()
        } catch (error: Exception) {
            runCatching { invite.reject(context) }
            finishSession(DisconnectCause.ERROR, "Could not answer: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun rejectInvite(id: String) {
        if (_activeCall.value.sessionId != id || _activeCall.value.state != CallState.Incoming) return
        finishSession(DisconnectCause.REJECTED)
    }

    fun hangup() {
        val active = _activeCall.value
        if (active.state == CallState.Disconnecting) return
        val call = twilioCall
        if (call == null) {
            finishSession(if (active.inbound) DisconnectCause.REJECTED else DisconnectCause.LOCAL)
            return
        }
        postDialJob?.cancel()
        _activeCall.update { it.copy(state = CallState.Disconnecting) }
        // Keep the system route until the SDK confirms it has stopped media.
        disconnectJob = scope.launch {
            delay(5_000)
            if (_activeCall.value.sessionId == active.sessionId && twilioCall === call) {
                DiagnosticLog.warn("Call disconnect", "SDK disconnect callback timed out")
                finishSession(DisconnectCause.LOCAL)
            }
        }
        runCatching { call.disconnect() }.onFailure {
            finishSession(DisconnectCause.ERROR, "Could not disconnect: ${it.javaClass.simpleName}")
        }
        syncCallService()
    }

    private fun finishSession(cause: Int, error: String? = null) {
        val previous = _activeCall.value
        incomingExpiryJob?.cancel()
        incomingExpiryJob = null
        postDialJob?.cancel()
        postDialJob = null
        disconnectJob?.cancel()
        disconnectJob = null
        pendingPostDialDigits = ""
        val retired = twilioCall
        twilioCall = null
        _activeCall.value = if (error == null) ActiveCall() else previous.copy(
            state = CallState.Failed, errorMessage = error, sessionId = null,
            reconnectStartedAtEpochMillis = null,
        )
        previous.sid?.let { sid ->
            InviteRegistry.take(sid)?.let { invite -> appContext?.let { runCatching { invite.reject(it) } } }
        }
        if (retired != null && retired.state != Call.State.DISCONNECTED) runCatching { retired.disconnect() }
        previous.sessionId?.let { TelecomCallManager.disconnect(it, DisconnectCause(cause, error)) }
        AudioRouter.endCall()
        appContext?.let(CallForegroundService::stop)
        DiagnosticLog.info("Call disconnect", "Session ended; call resources released")
    }

    fun toggleMute() { setMuted(!(twilioCall?.isMuted ?: return)) }
    fun setMuted(muted: Boolean) {
        val call = twilioCall ?: return
        if (call.isMuted == muted) return
        call.mute(muted)
        publishState()
    }

    internal fun onSystemAudioChanged(muted: Boolean) {
        val call = twilioCall
        if (call != null && call.isMuted != muted) call.mute(muted)
        _activeCall.update { it.copy(muted = muted, speaker = AudioRouter.isSpeakerOn) }
        // Do not feed audio callbacks back into Telecom.setActive or route selection.
    }

    fun toggleHold() { setHeld(!(twilioCall?.isOnHold ?: return)) }
    fun setHeld(held: Boolean) {
        val call = twilioCall ?: return
        if (call.isOnHold == held) return
        call.hold(held)
        val now = System.currentTimeMillis()
        _activeCall.update { current ->
            if (held) current.copy(held = true, holdStartedAtEpochMillis = now)
            else current.copy(
                held = false, holdStartedAtEpochMillis = null,
                totalHeldMillis = current.totalHeldMillis +
                    (current.holdStartedAtEpochMillis?.let { (now - it).coerceAtLeast(0) } ?: 0),
            )
        }
        publishState()
    }

    fun toggleSpeaker() { TelecomCallManager.toggleSpeakerRoute() }
    fun sendDtmf(digits: String) {
        if (_activeCall.value.state == CallState.Connected) twilioCall?.sendDigits(digits)
    }

    private fun syncCallService(): Boolean {
        val context = appContext ?: return false
        val call = _activeCall.value
        TelecomCallManager.sync(call)
        if (!call.isLive) {
            CallForegroundService.stop(context)
            return true
        }
        return runCatching { CallForegroundService.start(context); true }.getOrElse {
            finishSession(DisconnectCause.ERROR, "Android could not start the call service: ${it.javaClass.simpleName}")
            false
        }
    }

    private fun publishState() {
        val call = twilioCall ?: return
        if (_activeCall.value.state == CallState.Disconnecting) return
        val state = CallState.from(call)
        val now = System.currentTimeMillis()
        val previous = _activeCall.value
        if (state != previous.state) DiagnosticLog.info("Call state", "${previous.state} -> $state")
        _activeCall.update {
            it.copy(
                state = state, sid = call.sid ?: it.sid,
                // Keep the validated destination / invite caller rather than client identity.
                muted = call.isMuted, held = call.isOnHold, speaker = AudioRouter.isSpeakerOn,
                connectedAtEpochMillis = it.connectedAtEpochMillis ?: if (state == CallState.Connected) now else null,
                reconnectStartedAtEpochMillis = if (state == CallState.Reconnecting) it.reconnectStartedAtEpochMillis ?: now else null,
                networkLabel = NetworkMonitor.status.value.label,
            )
        }
        syncCallService()
    }

    private fun listener(id: String) = object : Call.Listener {
        // Posting guarantees accept/connect has assigned twilioCall before even an immediate SDK callback.
        private fun current(call: Call, action: () -> Unit) {
            main.post {
                if (_activeCall.value.sessionId == id && twilioCall === call) action()
            }
        }
        override fun onConnectFailure(call: Call, error: CallException) = current(call) {
            DiagnosticLog.error("Call connection", error.message ?: "Connection failed", error.errorCode.toString())
            finishSession(DisconnectCause.ERROR, error.message ?: "Voice connection failed")
        }
        override fun onRinging(call: Call) = current(call) { publishState() }
        override fun onConnected(call: Call) = current(call) {
            publishState()
            if (_activeCall.value.state == CallState.Connected) sendPendingPostDialDigits(call)
        }
        override fun onReconnecting(call: Call, error: CallException) = current(call) {
            DiagnosticLog.warn("Call connection", "Reconnecting", error.errorCode.toString())
            publishState()
        }
        override fun onReconnected(call: Call) = current(call) { publishState() }
        override fun onCallQualityWarningsChanged(
            call: Call, warnings: Set<Call.CallQualityWarning>, previousWarnings: Set<Call.CallQualityWarning>,
        ) = current(call) {
            _activeCall.update { it.copy(qualityWarnings = warnings.mapTo(linkedSetOf()) { warning -> warning.name.lowercase().replace('_', ' ') }) }
        }
        override fun onDisconnected(call: Call, error: CallException?) = current(call) {
            val local = _activeCall.value.state == CallState.Disconnecting
            finishSession(
                if (error != null) DisconnectCause.ERROR else if (local) DisconnectCause.LOCAL else DisconnectCause.REMOTE,
                error?.message,
            )
        }
    }

    private fun scheduleIncomingExpiry(callSid: String) {
        incomingExpiryJob?.cancel()
        incomingExpiryJob = scope.launch {
            delay(70_000)
            if (!_activeCall.value.matchesIncoming(callSid)) return@launch
            DiagnosticLog.warn("Incoming call", "Unanswered invite expired")
            finishSession(DisconnectCause.MISSED)
        }
    }

    private fun sendPendingPostDialDigits(call: Call) {
        val sequence = pendingPostDialDigits
        pendingPostDialDigits = ""
        if (sequence.isBlank()) return
        postDialJob?.cancel()
        postDialJob = scope.launch {
            val digits = StringBuilder()
            fun flush() {
                if (digits.isNotEmpty() && twilioCall === call) call.sendDigits(digits.toString())
                digits.clear()
            }
            for (character in sequence) {
                if (twilioCall !== call) return@launch
                if (character == ',') { flush(); delay(2_000) }
                else if (character.isDigit() || character == '*' || character == '#') digits.append(character)
            }
            flush()
        }
    }
}
