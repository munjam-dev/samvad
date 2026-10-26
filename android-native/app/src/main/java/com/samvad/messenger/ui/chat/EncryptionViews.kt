package com.samvad.messenger.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.samvad.core.e2ee.EncryptionBanner
import com.samvad.core.e2ee.LockReason
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.QuietButton
import com.samvad.messenger.ui.theme.SamvadTheme
import java.text.DateFormat
import java.util.Date

/**
 * The note at the top of a chat's history. It says exactly what is true for THIS chat: encrypted from the start,
 * encrypted from a later moment, not encrypted (and why, and whether it can be upgraded), or a group. The logic that
 * decides which one lives in [com.samvad.core.e2ee.E2eePolicy] and is tested there.
 */
@Composable
internal fun EncryptionNotice(
  banner: EncryptionBanner,
  otherName: String,
  sinceMs: Long?,
  upgrading: Boolean,
  verified: Boolean,
  onUpgrade: () -> Unit,
  onVerify: () -> Unit,
  onLearnMore: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = SamvadTheme.colors
  val encrypted = banner == EncryptionBanner.ENCRYPTED || banner == EncryptionBanner.ENCRYPTED_FROM_LATER
  val since = sinceMs?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }.orEmpty()
  val title = stringResource(
    when (banner) {
      EncryptionBanner.ENCRYPTED -> R.string.e2ee_notice_title_on
      EncryptionBanner.ENCRYPTED_FROM_LATER -> R.string.e2ee_notice_title_later
      EncryptionBanner.GROUP_NOT_ENCRYPTED -> R.string.e2ee_notice_title_group
      EncryptionBanner.PLAINTEXT_FEATURE_OFF -> R.string.privacy_notice_title
      EncryptionBanner.ENCRYPTED_UNSUPPORTED -> R.string.e2ee_notice_title_unsupported
      else -> R.string.e2ee_notice_title_plain
    },
  )
  val body = when (banner) {
    EncryptionBanner.ENCRYPTED -> stringResource(R.string.e2ee_notice_body_on, otherName)
    EncryptionBanner.ENCRYPTED_FROM_LATER -> stringResource(R.string.e2ee_notice_body_later, since, otherName)
    EncryptionBanner.GROUP_NOT_ENCRYPTED -> stringResource(R.string.e2ee_notice_body_group)
    EncryptionBanner.PLAINTEXT_FEATURE_OFF -> stringResource(R.string.privacy_notice_body)
    EncryptionBanner.ENCRYPTED_UNSUPPORTED -> stringResource(R.string.e2ee_notice_body_unsupported)
    EncryptionBanner.PLAINTEXT_PEER_WITHOUT_KEYS -> stringResource(R.string.e2ee_notice_body_peer_no_keys, otherName)
    EncryptionBanner.PLAINTEXT_CAN_UPGRADE -> stringResource(R.string.e2ee_notice_body_upgrade)
  }
  Column(
    modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(
      Modifier.widthIn(max = 340.dp).background(colors.chip, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(if (encrypted) Icons.Outlined.Lock else Icons.Outlined.LockOpen, null, tint = colors.onChip, modifier = Modifier.size(14.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, color = colors.onChip)
      }
      Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onChip, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
      if (banner == EncryptionBanner.PLAINTEXT_CAN_UPGRADE) {
        PrimaryButton(stringResource(R.string.e2ee_upgrade_action), onUpgrade, loading = upgrading, modifier = Modifier.padding(top = 8.dp))
      }
      if (encrypted) {
        QuietButton(stringResource(if (verified) R.string.e2ee_verified_action else R.string.e2ee_verify_action), onVerify)
      }
      QuietButton(stringResource(R.string.privacy_learn_more), onLearnMore)
    }
  }
}

/** Shown above the messages while the other person's security code has changed and nothing has been decided. */
@Composable
internal fun SecurityCodeChangedBanner(name: String, onReview: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 6.dp)
      .background(colors.tertiaryContainer, RoundedCornerShape(14.dp))
      .padding(horizontal = 14.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(Icons.Outlined.Warning, null, tint = colors.onTertiaryContainer, modifier = Modifier.size(20.dp))
    Column(Modifier.weight(1f)) {
      Text(stringResource(R.string.e2ee_code_changed_title), style = MaterialTheme.typography.labelLarge, color = colors.onTertiaryContainer)
      Text(stringResource(R.string.e2ee_code_changed_body, name), style = MaterialTheme.typography.bodySmall, color = colors.onTertiaryContainer)
    }
    QuietButton(stringResource(R.string.e2ee_code_changed_action), onReview)
  }
}

/** What an encrypted message that cannot be shown says in its place. */
@Composable
internal fun lockedMessageText(lock: String?): String = stringResource(
  when (runCatching { LockReason.valueOf(lock.orEmpty()) }.getOrNull()) {
    LockReason.IDENTITY_CHANGED -> R.string.e2ee_locked_identity
    LockReason.NO_SESSION -> R.string.e2ee_locked_no_session
    LockReason.OWN_MESSAGE_LOST -> R.string.e2ee_locked_own
    LockReason.DISABLED -> R.string.e2ee_locked_disabled
    else -> R.string.e2ee_locked_unreadable
  },
)

@Composable
internal fun e2eeNoticeText(notice: ConversationViewModel.E2eeNotice, name: String): String = when (notice) {
  ConversationViewModel.E2eeNotice.UPGRADED -> stringResource(R.string.e2ee_upgraded)
  ConversationViewModel.E2eeNotice.PEER_NO_KEYS -> stringResource(R.string.e2ee_peer_no_keys, name)
  ConversationViewModel.E2eeNotice.CODE_CHANGED -> stringResource(R.string.e2ee_send_blocked_code, name)
  ConversationViewModel.E2eeNotice.UNAVAILABLE -> stringResource(R.string.e2ee_unavailable)
  ConversationViewModel.E2eeNotice.NOT_AVAILABLE_HERE -> stringResource(R.string.e2ee_not_available_here)
}

/** "Learn more" for encrypted builds: what is protected, what is not, and the one-phone limit, in plain words. */
@Composable
internal fun EncryptionInfoSheet(onClose: () -> Unit) {
  val colors = SamvadTheme.colors
  Column(
    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
  ) {
    Text(stringResource(R.string.e2ee_sheet_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
    InfoSection(R.string.e2ee_sheet_protected_title, R.string.e2ee_sheet_protected)
    InfoSection(R.string.e2ee_sheet_visible_title, R.string.e2ee_sheet_visible)
    InfoSection(R.string.e2ee_sheet_not_title, R.string.e2ee_sheet_not)
    InfoSection(R.string.e2ee_sheet_phone_title, R.string.e2ee_sheet_phone)
    InfoSection(R.string.e2ee_sheet_report_title, R.string.e2ee_sheet_report)
    Spacer(Modifier.size(16.dp))
    PrimaryButton(stringResource(R.string.close), onClose)
  }
}

@Composable
private fun InfoSection(title: Int, body: Int) {
  val colors = SamvadTheme.colors
  Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = colors.onSurface, modifier = Modifier.padding(top = 16.dp))
  Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}
