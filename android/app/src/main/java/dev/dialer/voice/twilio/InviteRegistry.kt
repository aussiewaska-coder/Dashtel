package dev.dialer.voice.twilio

import com.twilio.voice.CallInvite
import java.util.concurrent.ConcurrentHashMap

/** Keeps live [CallInvite] objects reachable between the push and the answer. */
object InviteRegistry {

    private val invites = ConcurrentHashMap<String, CallInvite>()

    fun put(invite: CallInvite) {
        invites[invite.callSid] = invite
    }

    fun take(sid: String): CallInvite? = invites.remove(sid)

}
