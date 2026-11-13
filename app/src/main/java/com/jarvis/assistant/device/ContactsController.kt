package com.jarvis.assistant.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class ContactMatch(val displayName: String, val number: String)

/**
 * Contacts are queried only in response to a confirmed request, only for the name the
 * user asked about, and are never written to the memory database or sent anywhere.
 */
@Singleton
class ContactsController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun search(query: String, limit: Int = 5): List<ContactMatch> {
        if (!hasPermission()) return emptyList()
        val cleaned = query.trim().replace("%", "").replace("_", "")
        if (cleaned.isEmpty()) return emptyList()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val results = mutableListOf<ContactMatch>()

        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                arrayOf("%$cleaned%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(projection[0])
                val numberIndex = cursor.getColumnIndex(projection[1])
                while (cursor.moveToNext() && results.size < limit) {
                    val name = cursor.getString(nameIndex) ?: continue
                    val number = cursor.getString(numberIndex) ?: continue
                    if (number.isBlank()) continue
                    if (results.none { it.displayName == name && it.number == number }) {
                        results += ContactMatch(name, number)
                    }
                }
            }
        }
        return results
    }

    /** Exact-ish match used before dialling, to avoid surprising the user. */
    fun bestMatch(query: String): ContactMatch? {
        val matches = search(query)
        val needle = query.trim().lowercase()
        return matches.firstOrNull { it.displayName.lowercase() == needle }
            ?: matches.firstOrNull { it.displayName.lowercase().startsWith(needle) }
            ?: matches.firstOrNull()
    }

    /** True when the query is itself a dialable number. */
    fun looksLikeNumber(query: String): Boolean =
        query.count { it.isDigit() } >= 3 &&
            query.all { it.isDigit() || it in " +-()" }
}
