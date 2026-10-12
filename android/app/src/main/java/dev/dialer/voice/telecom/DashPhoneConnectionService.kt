package dev.dialer.voice.telecom

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import dev.dialer.voice.R
import dev.dialer.voice.call.AudioRouter
import dev.dialer.voice.diagnostics.DiagnosticLog
import dev.dialer.voice.model.CallState
import dev.dialer.voice.twilio.ActiveCall
import dev.dialer.voice.twilio.VoiceManager

class DashPhoneConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection =
        TelecomCallManager.createConnection(request, incoming = true)
    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection =
        TelecomCallManager.createConnection(request, incoming = false)
    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest) =
        TelecomCallManager.creationFailed(request)
    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest) =
        TelecomCallManager.creationFailed(request)
}

/** Main-thread coordinator. Android owns presentation and routing for every call. */
object TelecomCallManager {
    private const val EXTRA_SESSION = "dev.dialer.voice.extra.SESSION"
    private lateinit var context: Context
    private lateinit var telecom: TelecomManager
    private lateinit var accountHandle: PhoneAccountHandle
    private val main = Handler(Looper.getMainLooper())
    private val sessions = CallSessionGate()
    private var connection: DashPhoneConnection? = null
    private var timeout: Runnable? = null

    data class AccountStatus(val registered: Boolean?, val enabled: Boolean?, val incomingPermitted: Boolean?) {
        val diagnosticLabel get() = "registered=$registered enabled=$enabled incomingPermitted=$incomingPermitted"
    }

    fun init(appContext: Context) {
        if (::telecom.isInitialized) return
        context = appContext.applicationContext
        telecom = context.getSystemService(TelecomManager::class.java)
        accountHandle = PhoneAccountHandle(ComponentName(context, DashPhoneConnectionService::class.java), "dashphone-voip")
        runCatching {
            telecom.registerPhoneAccount(
                PhoneAccount.builder(accountHandle, context.getString(R.string.telecom_account_name))
                    .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                    .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL)).build(),
            )
            DiagnosticLog.info("System calling", accountStatus().diagnosticLabel)
        }.onFailure { DiagnosticLog.error("System calling", "Account registration failed: ${it.javaClass.simpleName}") }
    }

    fun accountStatus(): AccountStatus {
        if (!::telecom.isInitialized) return AccountStatus(false, false, false)
        // Android 12+ can deny getPhoneAccount without READ_PHONE_NUMBERS even
        // though Telecom can use the account. Android 15 provides an own-account API.
        val lookup = runCatching {
            if (Build.VERSION.SDK_INT >= 35) {
                telecom.registeredPhoneAccounts.firstOrNull { it.accountHandle == accountHandle }
            } else {
                telecom.getPhoneAccount(accountHandle)
            }
        }
        if (lookup.isFailure) {
            DiagnosticLog.warn("System calling", "Account status unavailable: ${lookup.exceptionOrNull()?.javaClass?.simpleName}")
            return AccountStatus(null, null, null)
        }
        val account = lookup.getOrNull()
        val permitted = account != null && (Build.VERSION.SDK_INT < 26 ||
            runCatching { telecom.isIncomingCallPermitted(accountHandle) }.getOrDefault(false))
        return AccountStatus(account != null, account?.isEnabled == true, permitted)
    }

    @SuppressLint("MissingPermission")
    fun submit(call: ActiveCall) {
        val id = call.sessionId ?: return
        if (!sessions.begin(id)) {
            VoiceManager.onTelecomFailure(id, "Another system call is still closing")
            return
        }
        if (accountStatus().enabled == false) {
            fail(id, "Enable DashPhone in Android Calling accounts")
            return
        }
        if (!call.inbound && ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            fail(id, "Allow the Phone permission to place Twilio calls")
            return
        }
        timeout = Runnable {
            if (sessions.isPending(id)) fail(id, "Android did not create the call within 12 seconds")
        }.also { main.postDelayed(it, 12_000) }
        runCatching {
            val callExtras = Bundle().apply { putString(EXTRA_SESSION, id) }
            val address = Uri.fromParts(PhoneAccount.SCHEME_TEL, call.number, null)
            if (call.inbound) {
                callExtras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, address)
                telecom.addNewIncomingCall(accountHandle, callExtras)
            } else {
                telecom.placeCall(address, Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
                    putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, callExtras)
                })
            }
            DiagnosticLog.info("System calling", "Handoff submitted; awaiting connection")
        }.onFailure { fail(id, "Android could not create the call: ${it.javaClass.simpleName}") }
    }

    private fun requestId(request: ConnectionRequest): String? = request.extras?.getString(EXTRA_SESSION)
        ?: request.extras?.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)?.getString(EXTRA_SESSION)

    internal fun createConnection(request: ConnectionRequest, incoming: Boolean): Connection {
        var requestedId = requestId(request)
        // Samsung dialler requests use the same validated Twilio-only path.
        if (requestedId == null && !incoming && sessions.id == null) {
            requestedId = VoiceManager.prepareOutgoing(request.address?.schemeSpecificPart.orEmpty())
            if (requestedId != null) sessions.begin(requestedId)
        }
        val id = requestedId
        val call = VoiceManager.activeCall.value
        if (id == null || call.sessionId != id || call.inbound != incoming || !call.isLive || !sessions.attach(id)) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.CANCELED, "Call is no longer available"))
        }
        cancelTimeout()
        val created = DashPhoneConnection(id, incoming)
        connection = created
        created.setAddress(Uri.fromParts(PhoneAccount.SCHEME_TEL, call.number, null), TelecomManager.PRESENTATION_ALLOWED)
        created.setCallerDisplayName(call.number, TelecomManager.PRESENTATION_ALLOWED)
        created.setAudioModeIsVoip(true)
        created.connectionCapabilities = Connection.CAPABILITY_MUTE or Connection.CAPABILITY_HOLD or Connection.CAPABILITY_SUPPORT_HOLD
        if (incoming) created.setRinging() else created.setDialing()
        DiagnosticLog.info("System calling", "Connection created; Android owns ringing and audio")
        main.post { if (isCurrent(created)) VoiceManager.onTelecomReady(id) }
        return created
    }

    internal fun creationFailed(request: ConnectionRequest) {
        requestId(request)?.let { fail(it, "Android rejected the call handoff") }
    }
    private fun fail(id: String, message: String) {
        if (!sessions.owns(id)) return
        disconnect(id, DisconnectCause(DisconnectCause.ERROR, message))
        VoiceManager.onTelecomFailure(id, message)
    }
    private fun cancelTimeout() {
        timeout?.let(main::removeCallbacks)
        timeout = null
    }
    private fun isCurrent(candidate: DashPhoneConnection): Boolean = connection === candidate && sessions.owns(candidate.sessionId)

    fun sync(call: ActiveCall) {
        val current = connection ?: return
        if (!sessions.owns(call.sessionId)) return
        val next = when (call.state) {
            CallState.Incoming -> Connection.STATE_RINGING
            CallState.Connecting, CallState.Ringing -> if (current.incoming) return else Connection.STATE_DIALING
            CallState.Connected, CallState.Reconnecting -> if (call.held) Connection.STATE_HOLDING else Connection.STATE_ACTIVE
            else -> return
        }
        // Avoid reissuing setActive on every mute, route or network update.
        if (current.state == next) return
        when (next) {
            Connection.STATE_RINGING -> current.setRinging()
            Connection.STATE_DIALING -> current.setDialing()
            Connection.STATE_ACTIVE -> current.setActive()
            Connection.STATE_HOLDING -> current.setOnHold()
        }
    }

    fun disconnect(id: String, cause: DisconnectCause) {
        if (!sessions.finish(id)) return
        cancelTimeout()
        val retired = connection
        connection = null
        retired?.let {
            it.setDisconnected(cause)
            it.destroy()
        }
        AudioRouter.endCall()
        DiagnosticLog.info("System calling", "Connection released")
    }

    fun toggleSpeakerRoute(): Boolean = selectAudioRoute(
        if (AudioRouter.isSpeakerOn) {
            AudioRouter.availableRoutes.value.firstOrNull { it.id == "bluetooth" || it.id == "wired" }?.id ?: "earpiece"
        } else "speaker",
    )

    @Suppress("DEPRECATION")
    fun selectAudioRoute(id: String): Boolean {
        val current = connection ?: return false
        if (Build.VERSION.SDK_INT < 26 || AudioRouter.availableRoutes.value.none { it.id == id }) return false
        val route = when (id) {
            "bluetooth" -> CallAudioState.ROUTE_BLUETOOTH
            "wired" -> CallAudioState.ROUTE_WIRED_HEADSET
            "speaker" -> CallAudioState.ROUTE_SPEAKER
            "earpiece" -> CallAudioState.ROUTE_EARPIECE
            else -> return false
        }
        current.setAudioRoute(route)
        return true
    }

    private class DashPhoneConnection(val sessionId: String, val incoming: Boolean) : Connection() {
        override fun onAnswer() { if (isCurrent(this)) VoiceManager.acceptInvite(sessionId) }
        override fun onAnswer(videoState: Int) = onAnswer()
        override fun onReject() { if (isCurrent(this)) VoiceManager.rejectInvite(sessionId) }
        override fun onDisconnect() { if (isCurrent(this)) VoiceManager.hangup() }
        override fun onAbort() = onDisconnect()
        override fun onHold() { if (isCurrent(this)) VoiceManager.setHeld(true) }
        override fun onUnhold() { if (isCurrent(this)) VoiceManager.setHeld(false) }
        override fun onPlayDtmfTone(c: Char) { if (isCurrent(this)) VoiceManager.sendDtmf(c.toString()) }
        override fun onStopDtmfTone() = Unit
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCallAudioStateChanged(state: CallAudioState) {
            if (!isCurrent(this)) return
            AudioRouter.updateFromTelecom(state)
            VoiceManager.onSystemAudioChanged(state.isMuted)
        }
    }
}
