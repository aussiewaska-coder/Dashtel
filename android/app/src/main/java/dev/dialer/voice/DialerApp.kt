package dev.dialer.voice

import android.app.Application
import dev.dialer.voice.data.ConfigStore
import dev.dialer.voice.data.TwilioRest
import dev.dialer.voice.network.NetworkMonitor
import dev.dialer.voice.notifications.Notifier
import dev.dialer.voice.telecom.TelecomCallManager
import dev.dialer.voice.twilio.VoiceManager

class DialerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ConfigStore.init(this)
        TwilioRest.init(this)
        dev.dialer.voice.data.SmsManager.init(this)
        NetworkMonitor.init(this)
        Notifier.createChannels(this)
        TelecomCallManager.init(this)
        VoiceManager.start(this)
    }
}
