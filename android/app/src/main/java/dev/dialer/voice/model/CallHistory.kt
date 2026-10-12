package dev.dialer.voice.model

/** One raw Twilio Call resource. A user-visible call can contain several legs. */
data class CallLeg(
    val sid: String,
    val parentCallSid: String?,
    val direction: String,
    val from: String,
    val to: String,
    val status: String,
    val startedAt: String?,
    val endedAt: String?,
    val durationSeconds: Long?,
)

/** Converts Twilio's parent/child call resources into one row per actual call. */
object CallHistory {

    fun group(
        legs: List<CallLeg>,
        recordingsByCall: Map<String, List<String>> = emptyMap(),
    ): List<CallRecord> {
        if (legs.isEmpty()) return emptyList()

        val bySid = legs.associateBy(CallLeg::sid)
        val groups = linkedMapOf<String, MutableList<CallLeg>>()
        legs.forEach { leg ->
            groups.getOrPut(rootSid(leg, bySid)) { mutableListOf() }.add(leg)
        }

        return groups.map { (rootSid, groupedLegs) ->
            val root = groupedLegs.firstOrNull { it.sid == rootSid }
            val inbound = root?.let(::isExternalInbound)
                ?: (groupedLegs.any(::isExternalInbound) || groupedLegs.any(::isClientDestination))
            val representative = if (inbound) {
                groupedLegs.firstOrNull(::isExternalInbound)
            } else {
                groupedLegs.firstOrNull(::isExternalOutbound)
            } ?: root ?: groupedLegs.first()

            CallRecord(
                sid = rootSid,
                direction = if (inbound) "inbound" else representative.direction,
                from = representative.from,
                to = representative.to,
                status = representative.status,
                startedAt = groupedLegs.mapNotNull(CallLeg::startedAt).minOrNull(),
                endedAt = groupedLegs.mapNotNull(CallLeg::endedAt).maxOrNull(),
                durationSeconds = groupedLegs.mapNotNull(CallLeg::durationSeconds).maxOrNull(),
                recordingSids = groupedLegs
                    .flatMap { recordingsByCall[it.sid].orEmpty() }
                    .distinct(),
                legSids = groupedLegs.map(CallLeg::sid),
            )
        }
    }

    private fun rootSid(leg: CallLeg, bySid: Map<String, CallLeg>): String {
        var current = leg
        val visited = mutableSetOf(current.sid)
        while (true) {
            val parentSid = current.parentCallSid?.takeIf(String::isNotBlank) ?: return current.sid
            if (!visited.add(parentSid)) return parentSid
            current = bySid[parentSid] ?: return parentSid
        }
    }

    private fun isClient(value: String): Boolean = value.startsWith("client:", ignoreCase = true)

    private fun isExternalInbound(leg: CallLeg): Boolean =
        leg.direction.startsWith("inbound", ignoreCase = true) && !isClient(leg.from)

    private fun isExternalOutbound(leg: CallLeg): Boolean =
        leg.direction.startsWith("outbound", ignoreCase = true) && !isClient(leg.to)

    private fun isClientDestination(leg: CallLeg): Boolean =
        leg.direction.startsWith("outbound", ignoreCase = true) && isClient(leg.to)
}
