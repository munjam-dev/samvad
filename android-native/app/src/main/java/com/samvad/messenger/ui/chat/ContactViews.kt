package com.samvad.messenger.ui.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.samvad.core.chat.ContactCard
import com.samvad.core.chat.ContactCards
import com.samvad.messenger.R
import com.samvad.messenger.ui.theme.SamvadTheme

/**
 * System contact picker for a single phone number (`ACTION_PICK` on the phone-data content type).
 * The data row Android hands back after the pick carries its own, temporary read grant, so this never
 * needs the READ_CONTACTS permission. Only one number comes back per pick, matching how people share a
 * card: the number they chose, not their whole book.
 */
@Composable
fun rememberDevicePhonePicker(onPicked: (name: String?, phone: String?) -> Unit): () -> Unit {
  val context = LocalContext.current
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    val uri = result.data?.data
    if (result.resultCode == Activity.RESULT_OK && uri != null) {
      val (name, phone) = readPickedPhone(context, uri)
      onPicked(name, phone)
    } else {
      onPicked(null, null)
    }
  }
  return {
    val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
    runCatching { launcher.launch(intent) }
  }
}

private fun readPickedPhone(context: android.content.Context, uri: Uri): Pair<String?, String?> {
  var name: String? = null
  var number: String? = null
  context.contentResolver.query(
    uri,
    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
    null, null, null,
  )?.use { c ->
    if (c.moveToFirst()) {
      name = c.getString(0)
      number = c.getString(1)
    }
  }
  return name to number
}

/** Opens the system "insert contact" screen pre-filled with [card], so the person can save it their way. */
fun saveContactIntent(card: ContactCard): Intent {
  val intent = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
    .putExtra(ContactsContract.Intents.Insert.NAME, card.name)
  card.organisation?.let { intent.putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
  card.phones.getOrNull(0)?.let { intent.putExtra(ContactsContract.Intents.Insert.PHONE, it) }
  card.phones.getOrNull(1)?.let { intent.putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it) }
  card.phones.getOrNull(2)?.let { intent.putExtra(ContactsContract.Intents.Insert.TERTIARY_PHONE, it) }
  return intent
}

/**
 * Page pattern: card bubble. Shows the shared contact with "Message" (only once this device has checked
 * whether the number belongs to a Samvad account) and "Save contact" (the system's own insert screen, so
 * nothing about the sender's book is read beyond what they chose to share).
 */
@Composable
fun ContactCardBubble(card: ContactCard, onUid: suspend () -> String?, onMessage: (String) -> Unit, onSave: () -> Unit) {
  val colors = SamvadTheme.colors
  var uid by remember(card) { mutableStateOf<String?>(null) }
  var checked by remember(card) { mutableStateOf(false) }
  androidx.compose.runtime.LaunchedEffect(card) {
    uid = onUid()
    checked = true
  }
  Column(
    Modifier
      .widthIn(min = 220.dp, max = 260.dp)
      .background(colors.surface, RoundedCornerShape(14.dp)),
  ) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      androidx.compose.foundation.layout.Box(
        Modifier.size(44.dp).background(colors.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
      ) { Icon(Icons.Outlined.PersonOutline, null, tint = colors.onPrimaryContainer) }
      Column(Modifier.weight(1f)) {
        Text(card.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        card.organisation?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1) }
        card.phones.forEach { phone ->
          Text(ContactCards.display(phone), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
      }
    }
    HorizontalDivider(color = colors.outlineVariant)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
      Row(
        Modifier.weight(1f).heightIn(min = 48.dp)
          .clickable(enabled = checked && uid != null) { uid?.let(onMessage) }
          .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(Icons.Outlined.Chat, null, tint = if (uid != null) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        Text(
          stringResource(R.string.contact_card_message),
          style = MaterialTheme.typography.labelLarge,
          color = if (uid != null) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f),
        )
      }
      androidx.compose.foundation.layout.Box(Modifier.width(1.dp).heightIn(min = 32.dp).background(colors.outlineVariant))
      Row(
        Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClick = onSave).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(Icons.Outlined.SaveAlt, null, tint = colors.primary, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.contact_card_save), style = MaterialTheme.typography.labelLarge, color = colors.primary)
      }
    }
  }
}
