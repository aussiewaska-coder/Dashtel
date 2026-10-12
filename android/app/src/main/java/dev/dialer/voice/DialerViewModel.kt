package dev.dialer.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dialer.voice.data.AppConfig
import dev.dialer.voice.data.ConfigStore
import dev.dialer.voice.data.ContactsRepository
import dev.dialer.voice.data.DeviceContact
import dev.dialer.voice.data.TwilioJwt
import dev.dialer.voice.data.TwilioRest
import dev.dialer.voice.diagnostics.DiagnosticLog
import dev.dialer.voice.model.CallRecord
import dev.dialer.voice.twilio.VoiceManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DialerUiState(
    val config: AppConfig = AppConfig(),
    val history: List<CallRecord> = emptyList(),
    val historyLoading: Boolean = false,
    val historyError: String? = null,
    val statusMessage: String? = null,
    val settingsBusy: Boolean = false,
    val contacts: List<DeviceContact> = emptyList(),
    val contactsLoading: Boolean = false,
    val contactsError: String? = null,
    val testCallCountdownSeconds: Int? = null,
)

/** Owns screen data and network work so Compose remains a rendering layer. */
class DialerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DialerUiState(config = ConfigStore.load()))
    val uiState: StateFlow<DialerUiState> = _uiState.asStateFlow()
    private var testCallJob: Job? = null

    fun refreshHistory() {
        val config = _uiState.value.config
        if (!config.isConfigured) {
            val message = "Complete Twilio Settings before loading call history"
            _uiState.update { it.copy(historyError = message) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(historyLoading = true, historyError = null) }
            runCatching { TwilioRest.listCalls(config) }
                .onSuccess { calls -> _uiState.update { it.copy(history = calls) } }
                .onFailure { error ->
                    val message = error.message ?: "Call history request failed"
                    DiagnosticLog.error("Call history", message)
                    _uiState.update { it.copy(historyError = message) }
                }
            _uiState.update { it.copy(historyLoading = false) }
        }
    }

    fun loadContacts(context: Context) {
        if (_uiState.value.contactsLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(contactsLoading = true, contactsError = null) }
            runCatching { ContactsRepository.load(context.applicationContext) }
                .onSuccess { contacts ->
                    _uiState.update { it.copy(contacts = contacts, contactsLoading = false) }
                }
                .onFailure { error ->
                    val message = error.message ?: "Could not read device contacts"
                    DiagnosticLog.warn("Contacts", message)
                    _uiState.update {
                        it.copy(contactsLoading = false, contactsError = message)
                    }
                }
        }
    }

    fun saveConfig(config: AppConfig) {
        runCatching { VoiceManager.saveConfig(config) }
            .onSuccess {
                _uiState.update {
                    it.copy(
                        config = config,
                        statusMessage = "Saved. Credentials are encrypted on this device.",
                    )
                }
                DiagnosticLog.info("Settings", "Saved credentials; ${DiagnosticLog.configSummary(config)}")
            }
            .onFailure { error ->
                val message = "Could not save settings: ${error.message ?: error.javaClass.simpleName}"
                DiagnosticLog.error("Settings", message)
                _uiState.update { it.copy(statusMessage = message) }
            }
    }

    fun testConfig(candidate: AppConfig) {
        viewModelScope.launch {
            _uiState.update { it.copy(settingsBusy = true, statusMessage = "Checking…") }
            DiagnosticLog.info("Settings test", "Starting; ${DiagnosticLog.configSummary(candidate)}")
            val result = runCatching {
                if (!candidate.canMintToken) error("Complete all Twilio fields first")
                val jwt = TwilioJwt.mint(
                    candidate.accountSid,
                    candidate.apiKeySid,
                    candidate.apiKeySecret,
                    candidate.twimlAppSid,
                    candidate.identity,
                    candidate.pushCredentialSid,
                )
                val claims = TwilioJwt.claims(jwt) ?: error("Minted token is not a JWT")
                if (!TwilioJwt.verify(jwt, candidate.apiKeySecret)) error("Signature check failed")
                val account = TwilioRest.verify(candidate)
                DiagnosticLog.info("Settings test", "JWT signature and Twilio REST authentication passed")
                val applicationSid = claims.optJSONObject("grants")
                    ?.optJSONObject("voice")
                    ?.optJSONObject("outgoing")
                    ?.optString("application_sid")
                    ?.take(6)
                "Keys work. Account: $account · " +
                    "iss=${claims.optString("iss").take(6)}… sub=${claims.optString("sub").take(6)}… " +
                    "app=$applicationSid…"
            }.getOrElse { error ->
                val apiError = error as? TwilioRest.TwilioApiError
                val code = apiError?.twilioCode
                    ?: apiError?.httpStatus?.toString()
                    ?: error.javaClass.simpleName
                val message = error.message ?: "Test failed"
                DiagnosticLog.error("Settings test", message, code)
                message
            }
            _uiState.update { it.copy(settingsBusy = false, statusMessage = result) }
        }
    }

    fun scheduleIncomingTestCall(candidate: AppConfig) {
        if (testCallJob?.isActive == true) return
        val validationError = when {
            !candidate.canMintToken -> "Complete all Twilio fields first"
            !candidate.hasPushCredential -> "Add the Push Credential SID before testing incoming calls"
            else -> null
        }
        if (validationError != null) {
            _uiState.update { it.copy(statusMessage = validationError) }
            return
        }

        // Save first so push registration and the REST request use the same identity.
        runCatching { VoiceManager.saveConfig(candidate) }
            .onFailure { error ->
                _uiState.update {
                    it.copy(statusMessage = "Could not save settings: ${error.message ?: error.javaClass.simpleName}")
                }
                return
            }
        _uiState.update { it.copy(config = candidate) }

        testCallJob = viewModelScope.launch {
            try {
                for (remaining in 10 downTo 1) {
                    _uiState.update {
                        it.copy(
                            settingsBusy = true,
                            testCallCountdownSeconds = remaining,
                            statusMessage = "Test call in $remaining seconds — turn off and lock the screen now",
                        )
                    }
                    delay(1_000)
                }
                _uiState.update {
                    it.copy(testCallCountdownSeconds = 0, statusMessage = "Requesting the incoming test call…")
                }
                val callSid = TwilioRest.createIncomingTestCall(candidate)
                DiagnosticLog.info("Incoming test", "Created Twilio call ${callSid.take(8)}…")
                _uiState.update {
                    it.copy(statusMessage = "Test call sent. It should ring now.")
                }
            } catch (error: Exception) {
                val message = error.message ?: "Could not create the test call"
                DiagnosticLog.error("Incoming test", message)
                _uiState.update { it.copy(statusMessage = message) }
            } finally {
                _uiState.update { it.copy(settingsBusy = false, testCallCountdownSeconds = null) }
                testCallJob = null
            }
        }
    }

    fun showStatus(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }
}
