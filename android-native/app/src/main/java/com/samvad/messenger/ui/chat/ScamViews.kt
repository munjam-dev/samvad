package com.samvad.messenger.ui.chat

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samvad.core.chat.Message
import com.samvad.core.chat.TimelineItem
import com.samvad.core.safety.FirstContact
import com.samvad.core.safety.LinkRisk
import com.samvad.core.safety.RiskLevel
import com.samvad.core.safety.ScamAssessment
import com.samvad.core.safety.ScamReason
import com.samvad.core.safety.ScamShield
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * Scam Shield views (Phase 9). Everything here runs on this device: message text is checked in memory by
 * core ScamShield, never sent anywhere and never logged. Nothing here says a link, message or person is verified.
 */

/** Opens links tapped in messages. A link flagged WARNING asks first, showing the real destination host. */
@Stable
internal class ScamLinks(private val open: (String) -> Unit) {
  /** The WARNING link waiting for the reader's answer; null when no dialog is showing. */
  var pending by mutableStateOf<LinkRisk?>(null)
    private set

  fun onTap(link: LinkRisk) {
    if (link.level == RiskLevel.WARNING) pending = link else open(link.openUrl)
  }

  fun confirm() {
    pending?.let { open(it.openUrl) }
    pending = null
  }

  fun cancel() {
    pending = null
  }
}

@Composable
internal fun rememberScamLinks(container: AppContainer): ScamLinks {
  val uri = LocalUriHandler.current
  val context = LocalContext.current
  return remember(container, uri) {
    ScamLinks { url ->
      container.appLock.expectExternal()
      runCatching { uri.openUri(url) }.onFailure {
        android.widget.Toast.makeText(context, R.string.scam_open_failed, android.widget.Toast.LENGTH_SHORT).show()
      }
    }
  }
}

/** The result for one message; clear for deleted, system, poll and empty messages. */
@Composable
internal fun rememberScamAssessment(m: Message): ScamAssessment =
  remember(m.id, m.text, m.deleted, m.kind) {
    if (m.deleted || m.text.isBlank() || m.kind == "system" || m.kind == "poll") ScamAssessment.CLEAR else ScamShield.assess(m.text)
  }

/** [base] with each link in the message made tappable (underlined, so it never relies on colour alone). */
@Composable
internal fun linkedText(base: AnnotatedString, assessment: ScamAssessment, links: ScamLinks?): AnnotatedString {
  if (links == null || assessment.links.isEmpty()) return base
  return remember(base, assessment, links) {
    buildAnnotatedString {
      append(base)
      assessment.links.forEach { l ->
        if (l.start >= 0 && l.end <= base.length && l.start < l.end) {
          addLink(
            LinkAnnotation.Clickable(
              tag = l.openUrl,
              styles = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold)),
              linkInteractionListener = { links.onTap(l) },
            ),
            l.start,
            l.end,
          )
        }
      }
    }
  }
}

private fun reasonText(reason: ScamReason): Int = when (reason) {
  ScamReason.LINK_IP_ADDRESS -> R.string.scam_reason_link_ip_address
  ScamReason.LINK_USERINFO -> R.string.scam_reason_link_userinfo
  ScamReason.LINK_LOOKALIKE_HOST -> R.string.scam_reason_link_lookalike_host
  ScamReason.LINK_HOMOGRAPH -> R.string.scam_reason_link_homograph
  ScamReason.LINK_PUNYCODE -> R.string.scam_reason_link_punycode
  ScamReason.LINK_SHORTENER -> R.string.scam_reason_link_shortener
  ScamReason.LINK_NOT_HTTPS -> R.string.scam_reason_link_not_https
  ScamReason.LINK_LONG_HOST -> R.string.scam_reason_link_long_host
  ScamReason.LINK_RISKY_TLD -> R.string.scam_reason_link_risky_tld
  ScamReason.LINK_MALFORMED_HOST -> R.string.scam_reason_link_malformed_host
  ScamReason.TEXT_SHARE_CODE -> R.string.scam_reason_text_share_code
  ScamReason.TEXT_KYC_VERIFY -> R.string.scam_reason_text_kyc_verify
  ScamReason.TEXT_PRIZE -> R.string.scam_reason_text_prize
  ScamReason.TEXT_GIFT_CARD -> R.string.scam_reason_text_gift_card
  ScamReason.TEXT_WORK_FROM_HOME -> R.string.scam_reason_text_work_from_home
  ScamReason.TEXT_URGENT_MONEY -> R.string.scam_reason_text_urgent_money
  ScamReason.TEXT_FEE_REQUEST -> R.string.scam_reason_text_fee_request
}

/** Small notice under an incoming bubble: what to be careful about, in plain words. Shows at most two reasons. */
@Composable
internal fun ScamNotice(assessment: ScamAssessment, modifier: Modifier = Modifier) {
  if (assessment.isClear) return
  val colors = SamvadTheme.colors
  val title = stringResource(if (assessment.hasLinkReason) R.string.scam_notice_link_title else R.string.scam_notice_message_title)
  val reasons = assessment.reasons.take(2).map { stringResource(reasonText(it)) }
  val description = stringResource(R.string.scam_notice_cd, title + " " + reasons.joinToString(" "))
  Row(
    modifier
      .widthIn(max = 300.dp)
      .background(colors.surfaceContainer, RoundedCornerShape(12.dp))
      .semantics(mergeDescendants = true) { contentDescription = description }
      .padding(horizontal = 10.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
      Icons.Outlined.WarningAmber,
      contentDescription = null,
      tint = if (assessment.level == RiskLevel.WARNING) colors.error else colors.onSurfaceVariant,
      modifier = Modifier.size(20.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(title, style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
      reasons.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
    }
  }
}

/** Asks before a WARNING link opens, showing where it really goes. Call once per screen. */
@Composable
internal fun ScamLinkDialog(links: ScamLinks) {
  val link = links.pending ?: return
  val colors = SamvadTheme.colors
  AlertDialog(
    onDismissRequest = links::cancel,
    icon = { Icon(Icons.Outlined.WarningAmber, null, tint = colors.error) },
    title = { Text(stringResource(R.string.scam_open_title)) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.scam_open_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Text(stringResource(R.string.scam_open_host_label), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Text(
          link.host.ifEmpty { link.raw },
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = colors.onSurface,
        )
        link.reasons.take(2).forEach {
          Text(stringResource(reasonText(it)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
      }
    },
    confirmButton = { TextButton(onClick = links::confirm) { Text(stringResource(R.string.scam_open_confirm), color = colors.error) } },
    dismissButton = { TextButton(onClick = links::cancel) { Text(stringResource(R.string.scam_open_cancel)) } },
  )
}

/** Whether to show the new-contact banner, and how to dismiss it for this chat. */
@Stable
internal class FirstContactUi(val show: Boolean, val dismiss: () -> Unit)

/**
 * The new-contact banner decision. Uses chat metadata only: whether the other person is in my saved contacts, who has
 * sent messages, and whether I dismissed the banner here (kept in local preferences).
 */
@Composable
internal fun rememberFirstContact(
  container: AppContainer,
  conversationId: String,
  otherUid: String?,
  state: ConversationUiState,
  blocked: Boolean,
): FirstContactUi {
  val scope = rememberCoroutineScope()
  val saved = remember(container) { container.savedNames?.state }?.collectAsStateWithLifecycle()?.value
  val dismissedFlow = remember(container, conversationId) { container.scamPrefs.dismissedChats.map { conversationId in it } }
  val dismissed = dismissedFlow.collectAsStateWithLifecycle(initialValue = null).value
  var incoming = 0
  var outgoing = 0
  state.items.forEach { if (it is TimelineItem.Bubble && !it.message.deleted) if (it.mine) outgoing++ else incoming++ }
  val show = FirstContact.shouldShow(
    isGroup = state.isGroup || otherUid == null,
    contactsKnown = saved?.available == true,
    isSaved = otherUid != null && saved?.byUid?.containsKey(otherUid) == true,
    incomingCount = incoming,
    outgoingCount = outgoing,
    allLoaded = !state.loading && !state.hasMore,
    dismissed = dismissed,
    blockedByMe = blocked,
  )
  return remember(show, conversationId) { FirstContactUi(show) { scope.launch { container.scamPrefs.dismissFirstContact(conversationId) } } }
}

/** Top of a chat with someone who is not a saved contact and has not been written to yet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FirstContactBanner(name: String, onBlock: () -> Unit, onReport: () -> Unit, onDismiss: () -> Unit) {
  val colors = SamvadTheme.colors
  Column(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 4.dp, vertical = 8.dp)
      .background(colors.surfaceContainer, RoundedCornerShape(16.dp))
      .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(Icons.Outlined.Info, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
      Text(stringResource(R.string.scam_first_title), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
    }
    Text(
      stringResource(R.string.scam_first_body, name),
      style = MaterialTheme.typography.bodyMedium,
      color = colors.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp, end = 8.dp),
    )
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      TextButton(onClick = onBlock) { Text(stringResource(R.string.chat_block), color = colors.error) }
      TextButton(onClick = onReport) { Text(stringResource(R.string.chat_report), color = colors.error) }
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.scam_first_dismiss), color = colors.primary) }
    }
  }
}
