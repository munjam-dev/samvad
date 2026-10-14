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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.samvad.messenger.BuildConfig
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.QuietButton
import com.samvad.messenger.ui.theme.SamvadTheme

/**
 * Whether messages are end-to-end encrypted. False until Phase 4 ships and is verified; the notice
 * wording switches on this one flag so the app never claims a protection it doesn't provide.
 */
private const val E2EE_ENABLED = false

/** The small notice at the top of every chat's history (US-009). */
@Composable
internal fun PrivacyNotice(onLearnMore: () -> Unit, modifier: Modifier = Modifier) {
  val colors = SamvadTheme.colors
  Column(
    modifier
      .fillMaxWidth()
      .padding(horizontal = 24.dp, vertical = 12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(
      Modifier
        .widthIn(max = 340.dp)
        .background(colors.chip, RoundedCornerShape(14.dp))
        .padding(horizontal = 14.dp, vertical = 10.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.Lock, null, tint = colors.onChip, modifier = Modifier.size(14.dp))
        Text(
          stringResource(if (E2EE_ENABLED) R.string.privacy_notice_title_e2ee else R.string.privacy_notice_title),
          style = MaterialTheme.typography.labelLarge,
          color = colors.onChip,
        )
      }
      Text(
        stringResource(if (E2EE_ENABLED) R.string.privacy_notice_body_e2ee else R.string.privacy_notice_body),
        style = MaterialTheme.typography.bodySmall,
        color = colors.onChip,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp),
      )
      QuietButton(stringResource(R.string.privacy_learn_more), onLearnMore)
    }
  }
}

/** "Learn more": what is protected today, what is not yet, and what changes next. */
@Composable
internal fun PrivacyInfoSheet(onClose: () -> Unit) {
  val colors = SamvadTheme.colors
  val uri = LocalUriHandler.current
  Column(
    Modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
  ) {
    Text(stringResource(R.string.privacy_sheet_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
    Section(R.string.privacy_sheet_now_title, R.string.privacy_sheet_now)
    if (!E2EE_ENABLED) {
      Section(R.string.privacy_sheet_not_title, R.string.privacy_sheet_not)
      Section(R.string.privacy_sheet_next_title, R.string.privacy_sheet_next)
    }
    Text(
      stringResource(R.string.privacy_sheet_depends),
      style = MaterialTheme.typography.bodyMedium,
      color = colors.onSurfaceVariant,
      modifier = Modifier.padding(top = 16.dp),
    )
    Spacer(Modifier.size(16.dp))
    QuietButton(stringResource(R.string.phone_privacy), { uri.openUri(BuildConfig.PRIVACY_URL) })
    PrimaryButton(stringResource(R.string.close), onClose)
  }
}

@Composable
private fun Section(title: Int, body: Int) {
  val colors = SamvadTheme.colors
  Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = colors.onSurface, modifier = Modifier.padding(top = 16.dp))
  Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}
