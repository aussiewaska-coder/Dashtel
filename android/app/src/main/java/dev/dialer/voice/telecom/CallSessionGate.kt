package dev.dialer.voice.telecom

/** Main-thread handoff state; retired calls cannot acquire or release a new call. */
internal class CallSessionGate {
    var id: String? = null
        private set
    private var attached = false
    fun begin(sessionId: String): Boolean {
        if (id != null) return false
        id = sessionId
        attached = false
        return true
    }
    fun owns(sessionId: String?): Boolean = sessionId != null && id == sessionId
    fun isPending(sessionId: String): Boolean = owns(sessionId) && !attached
    fun attach(sessionId: String): Boolean {
        if (!isPending(sessionId)) return false
        attached = true
        return true
    }
    fun finish(sessionId: String): Boolean {
        if (!owns(sessionId)) return false
        id = null
        attached = false
        return true
    }
}
