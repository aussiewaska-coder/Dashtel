package dev.dialer.voice.diagnostics

import dev.dialer.voice.data.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-memory support log. Never records credential values or full phone numbers. */
object DiagnosticLog {
    private const val MAX_ENTRIES = 100
    private const val MAX_MESSAGE_LENGTH = 400
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    fun info(area: String, message: String) = record("INFO", area, message)

    fun warn(area: String, message: String, code: String? = null) = record("WARN", area, message, code)

    fun error(area: String, message: String, code: String? = null) = record("ERROR", area, message, code)

    fun configSummary(config: AppConfig): String = listOf(
        "accountSid=${suffix(config.accountSid)}",
        "apiKeySid=${suffix(config.apiKeySid)}",
        "apiKeySecret=${if (config.apiKeySecret.isBlank()) "missing" else "set (not logged)"}",
        "twimlAppSid=${suffix(config.twimlAppSid)}",
        "phoneNumber=${maskPhone(config.phoneNumber)}",
        "identity=${sanitize(config.identity)}",
        "pushCredentialSid=${suffix(config.pushCredentialSid)}",
    ).joinToString(" ")

    fun export(visibleErrors: List<String> = emptyList()): String = synchronized(lock) {
        buildString {
            appendLine("DashPhone diagnostics")
            appendLine("Credential values and full phone numbers are excluded.")
            if (visibleErrors.isNotEmpty()) {
                appendLine("Current app error messages:")
                visibleErrors.forEach { appendLine(sanitize(it)) }
            }
            if (_entries.value.isEmpty()) appendLine("No diagnostic events recorded yet.")
            else _entries.value.forEach(::appendLine)
        }
    }

    private fun record(level: String, area: String, message: String, code: String? = null) {
        val timestamp = synchronized(lock) { formatter.format(Date()) }
        val safeMessage = sanitize(message)
        val codePart = code?.takeIf { it.isNotBlank() }?.let { " code=${sanitize(it)}" }.orEmpty()
        val entry = "$timestamp $level [$area]$codePart $safeMessage"
        android.util.Log.i("DashPhone", entry)
        synchronized(lock) {
            _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        }
    }

    private fun sanitize(input: String): String {
        var value = input.replace(Regex("[\\r\\n\\t]+"), " ").take(MAX_MESSAGE_LENGTH)
        value = value.replace(
            Regex("(?i)(api[_ -]?key[_ -]?secret|auth[_ -]?token|authorization)\\s*[:=]\\s*[^\\s,;]+"),
            "$1=[redacted]",
        )
        value = value.replace(
            Regex("(?i)bearer\\s+[a-z0-9._~+/=-]+"),
            "Bearer [redacted]",
        )
        value = value.replace(
            Regex("\\beyJ[a-zA-Z0-9_-]{8,}\\.[a-zA-Z0-9_-]{8,}\\.[a-zA-Z0-9_-]{8,}\\b"),
            "[JWT redacted]",
        )
        return value.replace(Regex("\\+\\d{7,15}"), "[phone redacted]")
    }

    private fun suffix(value: String): String = when {
        value.isBlank() -> "missing"
        value.length <= 6 -> "set"
        else -> "…${value.takeLast(4)}"
    }

    private fun maskPhone(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length < 7) return if (trimmed.isBlank()) "missing" else "set"
        return "${trimmed.take(3)}…${trimmed.takeLast(2)}"
    }
}
