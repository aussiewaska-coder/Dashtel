package dev.dialer.voice.call

import android.telecom.CallAudioState
import dev.dialer.voice.telecom.TelecomCallManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observes system routing. Telecom alone owns audio focus and Bluetooth SCO. */
object AudioRouter {
    data class Route(val id: String, val label: String)
    private val _audioRoute = MutableStateFlow("Phone")
    val audioRoute = _audioRoute.asStateFlow()
    private val _availableRoutes = MutableStateFlow<List<Route>>(emptyList())
    val availableRoutes = _availableRoutes.asStateFlow()
    var isSpeakerOn = false
        private set

    fun selectRoute(id: String): Boolean = TelecomCallManager.selectAudioRoute(id)

    fun endCall() {
        isSpeakerOn = false
        _audioRoute.value = "Phone"
        _availableRoutes.value = emptyList()
    }

    @Suppress("DEPRECATION")
    fun updateFromTelecom(state: CallAudioState) {
        val routes = listOf(
            CallAudioState.ROUTE_EARPIECE to Route("earpiece", "Phone earpiece"),
            CallAudioState.ROUTE_BLUETOOTH to Route("bluetooth", "Bluetooth"),
            CallAudioState.ROUTE_WIRED_HEADSET to Route("wired", "Wired / USB headset"),
            CallAudioState.ROUTE_SPEAKER to Route("speaker", "Speakerphone"),
        )
        _availableRoutes.value = routes.filter { (mask, _) -> state.supportedRouteMask and mask != 0 }.map { it.second }
        _audioRoute.value = routes.firstOrNull { it.first == state.route }?.second?.label ?: "System call audio"
        isSpeakerOn = state.route == CallAudioState.ROUTE_SPEAKER
    }
}
