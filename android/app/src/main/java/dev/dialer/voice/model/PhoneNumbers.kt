package dev.dialer.voice.model

private val SUPPORTED_E164 = Regex("^\\+(?:61[1-9]\\d{5,9}|66[1-9]\\d{7,8}|855[1-9]\\d{7,8})$")

data class DialPlan(
    val destination: String,
    /** Digits sent after connection. Commas represent two-second pauses. */
    val postDialDigits: String = "",
)

/** Keeps only characters with a meaning in a telephone number or post-dial sequence. */
fun cleanDialInput(input: String): String = buildString {
    input.forEach { character ->
        when {
            character.isDigit() -> append(character)
            character == '+' && isEmpty() -> append(character)
            character == '*' || character == '#' || character == ',' -> append(character)
            character == 'p' || character == 'P' -> append(',')
        }
    }
}.take(40)

/** Parses a number and optional comma-separated extension/pause sequence. */
fun createDialPlan(input: String): DialPlan? {
    val cleaned = cleanDialInput(input)
    val pauseIndex = cleaned.indexOf(',')
    val numberPart = if (pauseIndex >= 0) cleaned.take(pauseIndex) else cleaned
    val postDial = if (pauseIndex >= 0) {
        cleaned.drop(pauseIndex).filter { it.isDigit() || it == '*' || it == '#' || it == ',' }
    } else {
        ""
    }
    val destination = normalizeForDial(numberPart) ?: return null
    return DialPlan(destination, postDial)
}

/** Accepts +61, +66 and +855; unprefixed national numbers default to Australia. */
fun normalizeForDial(input: String): String? {
    val dialable = input.trim().filter { it.isDigit() || it == '+' }
    if (dialable.isBlank()) return null
    if (dialable.startsWith("+")) return dialable.takeIf(SUPPORTED_E164::matches)

    val local = dialable.filter(Char::isDigit)
    val normalized = when {
        local.startsWith("0061") || local.startsWith("0066") || local.startsWith("00855") ->
            "+${local.drop(2)}"
        local.length == 10 && local.startsWith('0') -> "+61${local.drop(1)}"
        local.length == 9 && !local.startsWith('0') -> "+61$local"
        local.length == 10 && (local.startsWith("1300") || local.startsWith("1800")) -> "+61$local"
        local.length == 6 && local.startsWith("13") -> "+61$local"
        else -> null
    }
    return normalized?.takeIf(SUPPORTED_E164::matches)
}

/** Readable Australian formatting. */
fun formatPhoneNumber(value: String): String {
    val normalized = normalizeForDial(value) ?: return value
    return when {
        normalized.startsWith("+614") && normalized.length == 12 ->
            "${normalized.take(3)} ${normalized.substring(3, 6)} ${normalized.substring(6, 9)} ${normalized.substring(9)}"
        normalized.startsWith("+61") && normalized.length == 12 ->
            "${normalized.take(3)} ${normalized.substring(3, 4)} ${normalized.substring(4, 8)} ${normalized.substring(8)}"
        else -> normalized
    }
}

fun t9Digits(value: String): String = value.uppercase().mapNotNull { character ->
    when (character) {
        in 'A'..'C' -> '2'
        in 'D'..'F' -> '3'
        in 'G'..'I' -> '4'
        in 'J'..'L' -> '5'
        in 'M'..'O' -> '6'
        in 'P'..'S' -> '7'
        in 'T'..'V' -> '8'
        in 'W'..'Z' -> '9'
        else -> null
    }
}.joinToString("")
