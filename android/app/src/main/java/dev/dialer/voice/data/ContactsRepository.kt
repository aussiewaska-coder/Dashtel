package dev.dialer.voice.data

import android.content.Context
import android.provider.ContactsContract
import dev.dialer.voice.model.normalizeForDial
import dev.dialer.voice.model.t9Digits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceContact(
    val id: Long,
    val name: String,
    val number: String,
    val type: String,
) {
    val initials: String
        get() = name.split(Regex("\\s+")).filter(String::isNotBlank).take(2)
            .mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("").ifBlank { "?" }

    fun matches(query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        val plainNumber = number.filter(Char::isDigit)
        val plainQuery = needle.filter(Char::isDigit)
        return name.contains(needle, ignoreCase = true) ||
            number.contains(needle) ||
            (plainQuery.isNotEmpty() && plainNumber.contains(plainQuery)) ||
            (plainQuery.isNotEmpty() && t9Digits(name).contains(plainQuery))
    }
}

object ContactsRepository {
    suspend fun load(context: Context): List<DeviceContact> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        val contacts = mutableListOf<DeviceContact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(projection[0])
            val nameColumn = cursor.getColumnIndexOrThrow(projection[1])
            val numberColumn = cursor.getColumnIndexOrThrow(projection[2])
            val typeColumn = cursor.getColumnIndexOrThrow(projection[3])
            val labelColumn = cursor.getColumnIndexOrThrow(projection[4])
            val seen = mutableSetOf<Pair<Long, String>>()
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val rawNumber = cursor.getString(numberColumn).orEmpty()
                val comparable = normalizeForDial(rawNumber) ?: rawNumber.filter(Char::isDigit)
                if (comparable.isBlank() || !seen.add(id to comparable)) continue
                val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                    context.resources,
                    cursor.getInt(typeColumn),
                    cursor.getString(labelColumn),
                ).toString()
                contacts += DeviceContact(
                    id = id,
                    name = cursor.getString(nameColumn).orEmpty().ifBlank { rawNumber },
                    number = rawNumber,
                    type = type,
                )
            }
        }
        contacts
    }
}
