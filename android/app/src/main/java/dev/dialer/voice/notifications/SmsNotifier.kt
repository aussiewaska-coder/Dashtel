package dev.dialer.voice.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.dialer.voice.MainActivity
import dev.dialer.voice.model.SmsMessage

object SmsNotifier {
    const val ACTION = "dev.dialer.voice.OPEN_MESSAGES"
    const val PEER = "message_peer"
    private const val CHANNEL = "sms_messages"
    fun init(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming text messages to your Twilio number"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            })
    }

    fun show(context: Context, peer: String, messages: List<SmsMessage>) {
        if (messages.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION)
            .setData("dashtel://messages/${android.net.Uri.encode(peer)}".toUri()).putExtra(PEER, peer)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
        val sender = Person.Builder().setName(peer).build()
        messages.takeLast(8).forEach {
            style.addMessage(it.body.ifEmpty { "Media message" }, it.timestamp, sender)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat).setStyle(style)
            .setContentTitle(peer).setContentText(messages.last().body)
            .setContentIntent(pending).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java).notify("sms:$peer", 1, notification)
    }

    fun cancel(context: Context, peer: String) = context.getSystemService(NotificationManager::class.java).cancel("sms:$peer", 1)

    fun clear(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag?.startsWith("sms:") == true }.forEach { manager.cancel(it.tag, it.id) }
    }
}
