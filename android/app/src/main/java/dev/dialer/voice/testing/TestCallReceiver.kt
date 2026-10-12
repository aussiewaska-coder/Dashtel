package dev.dialer.voice.testing

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.dialer.voice.data.ConfigStore
import dev.dialer.voice.diagnostics.DiagnosticLog
import dev.dialer.voice.model.normalizeForDial
import dev.dialer.voice.twilio.VoiceManager

/** Permanent ADB call trigger, protected by Android's signature-level DUMP permission. */
class TestCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val number = normalizeForDial(intent.getStringExtra(EXTRA_NUMBER).orEmpty())
            ?: return fail("Destination must be E.164 or a supported Australian local number")
        if (!ConfigStore.load().isConfigured) {
            return fail("Twilio Settings are incomplete")
        }
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return fail("Microphone permission is not granted; open the app and allow it first")
        }

        DiagnosticLog.info("ADB test call", "ADB requested an outgoing call")
        VoiceManager.dial(number)
        resultCode = Activity.RESULT_OK
        resultData = "Dial request accepted"
    }

    private fun fail(message: String) {
        DiagnosticLog.warn("ADB test call", message)
        resultCode = Activity.RESULT_CANCELED
        resultData = message
    }

    companion object {
        private const val EXTRA_NUMBER = "number"
    }
}
