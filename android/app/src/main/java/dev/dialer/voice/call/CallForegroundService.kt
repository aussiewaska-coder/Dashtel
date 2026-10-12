package dev.dialer.voice.call

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.dialer.voice.notifications.Notifier
import dev.dialer.voice.twilio.ActiveCall
import dev.dialer.voice.twilio.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CallForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lifecycleJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Fulfil startForegroundService even if a cancellation beat this start command.
        updateForeground(VoiceManager.activeCall.value)
        if (!VoiceManager.activeCall.value.isLive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DashPhone:active-call")
                .apply { setReferenceCounted(false); acquire(8 * 60 * 60 * 1_000L) }
        }
        if (lifecycleJob == null) lifecycleJob = scope.launch {
            VoiceManager.activeCall.collect { call ->
                if (call.isLive) updateForeground(call)
                else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun updateForeground(call: ActiveCall) {
        ServiceCompat.startForeground(
            this, Notifier.CALL_ID, Notifier.callServiceNotification(this, call),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0,
        )
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CallForegroundService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
