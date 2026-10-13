package com.samvad.messenger.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableOptions
import com.samvad.core.phone.PhoneNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class DeviceContact(val name: String, val e164: String)

data class SamvadContact(val name: String, val e164: String, val uid: String)

class LookupLimitException : Exception("lookup limit reached")

/**
 * Reads the address book (after the user grants permission) and matches numbers through the
 * rate-limited lookupPhoneNumbers callable. Results are cached for the session to save the budget.
 */
class ContactsRepository(private val context: Context, private val functions: FirebaseFunctions) {
  private val cache = mutableMapOf<String, String?>()
  private val limitedUse = HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(true).build()

  fun hasPermission(): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

  suspend fun deviceContacts(): List<DeviceContact> = withContext(Dispatchers.IO) {
    if (!hasPermission()) return@withContext emptyList()
    val out = LinkedHashMap<String, DeviceContact>()
    context.contentResolver.query(
      ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
      arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
      ),
      null,
      null,
      ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " ASC",
    )?.use { c ->
      while (c.moveToNext()) {
        val name = c.getString(0) ?: continue
        val e164 = PhoneNumbers.normalize(c.getString(1) ?: continue) ?: continue
        out.putIfAbsent(e164, DeviceContact(name, e164))
      }
    }
    out.values.toList()
  }

  /** Maps E.164 numbers to Samvad UIDs (absent when not registered). */
  suspend fun lookup(numbers: List<String>): Map<String, String> {
    val unknown = numbers.distinct().filter { it !in cache }
    for (chunk in unknown.chunked(50)) {
      try {
        val result = functions.getHttpsCallable("lookupPhoneNumbers", limitedUse)
          .call(mapOf("phoneNumbers" to chunk)).await()
        @Suppress("UNCHECKED_CAST")
        val matches = ((result.data as? Map<String, Any?>)?.get("matches") as? List<Map<String, Any?>>).orEmpty()
        val found = matches.associate { (it["phoneE164"] as String) to (it["uid"] as String) }
        chunk.forEach { cache[it] = found[it] }
      } catch (e: FirebaseFunctionsException) {
        if (e.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED) throw LookupLimitException()
        throw e
      }
    }
    return numbers.mapNotNull { n -> cache[n]?.let { n to it } }.toMap()
  }
}
