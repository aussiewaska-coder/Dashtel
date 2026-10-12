package dev.dialer.voice.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.dialer.voice.MainActivity
import dev.dialer.voice.R
import dev.dialer.voice.model.CallState
import dev.dialer.voice.twilio.ActiveCall

/** Telecom owns call alerts. This object supplies only FGS disclosure and handoff errors. */
object Notifier {
    private const val CALL_CHANNEL = "call"
    private const val ERROR_CHANNEL = "call_errors"
    const val CALL_ID = 4101

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        // Retire channels belonging to the removed app-owned ringing/missed-call path.
        listOf("incoming_call", "incoming_call_v2", "incoming_call_v3", "incoming_call_v4", "missed_calls")
            .forEach(manager::deleteNotificationChannel)
        manager.cancel(4100)
        manager.cancel(4102)
        manager.createNotificationChannel(NotificationChannel(CALL_CHANNEL, "Call service", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ERROR_CHANNEL, "Call setup problems", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun callServiceNotification(context: Context, call: ActiveCall): Notification {
        val status = when (call.state) {
            CallState.Incoming -> "Incoming Twilio call"
            CallState.Connecting -> "Connecting"
            CallState.Ringing -> "Ringing"
            CallState.Connected -> if (call.held) "On hold" else "Connected"
            CallState.Reconnecting -> "Reconnecting"
            CallState.Disconnecting -> "Ending call"
            else -> "Call service"
        }
        return NotificationCompat.Builder(context, CALL_CHANNEL)
            .setContentTitle("DashPhone")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_phone)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    fun showCallFailure(context: Context, message: String) {
        val tap = PendingIntent.getActivity(context, 10,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, ERROR_CHANNEL)
            .setContentTitle("Incoming call could not be shown")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(4103, notification) }
    }
}
