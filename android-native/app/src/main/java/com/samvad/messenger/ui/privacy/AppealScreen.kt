package com.samvad.messenger.ui.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samvad.core.privacy.Appeal
import com.samvad.core.privacy.AppealKind
import com.samvad.core.privacy.AppealStatus
import com.samvad.core.privacy.Appeals
import com.samvad.core.time.TimeLabels
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.data.SubmitAppealResult
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.SamvadTextField
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.launch

/**
 * Settings → Help → Appeal a decision. Pattern: Form (vectorlab-ux-skills:page-patterns #3): the
 * user's one job is writing and sending an appeal. The list of past appeals is supporting context
 * for that job (their own status history), not a second page.
 */
@Composable
fun AppealScreen(container: AppContainer, me: String, onBack: () -> Unit) {
  val colors = SamvadTheme.colors
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val appealsRepo = container.appeals
  var kind by remember { mutableStateOf(AppealKind.OTHER) }
  var message by remember { mutableStateOf("") }
  var sending by remember { mutableStateOf(false) }
  val msgSent = stringResource(R.string.appeal_sent)
  val msgTooMany = stringResource(R.string.appeal_too_many)
  val msgFailed = stringResource(R.string.appeal_failed)
  val past = appealsRepo?.let { remember(me) { it.myAppeals(me) } }?.collectAsStateWithLifecycle(emptyList())?.value.orEmpty()
  val invalid = Appeals.messageProblem(message)

  Column(Modifier.fillMaxSize().background(colors.background)) {
    Row(Modifier.statusBarsPadding().padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
      Text(stringResource(R.string.appeal_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
    }
    Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().imePadding(),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(stringResource(R.string.appeal_intro), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)

      FormGroup(stringResource(R.string.appeal_kind_label)) {
        KindRow(stringResource(R.string.appeal_kind_report_decision), kind == AppealKind.REPORT_DECISION) { kind = AppealKind.REPORT_DECISION }
        KindRow(stringResource(R.string.appeal_kind_account_restriction), kind == AppealKind.ACCOUNT_RESTRICTION) { kind = AppealKind.ACCOUNT_RESTRICTION }
        KindRow(stringResource(R.string.appeal_kind_other), kind == AppealKind.OTHER) { kind = AppealKind.OTHER }
      }

      SamvadTextField(
        value = message,
        onValueChange = { if (it.length <= Appeals.MAX_MESSAGE) message = it },
        label = stringResource(R.string.appeal_message_label),
        maxLength = Appeals.MAX_MESSAGE,
        singleLine = false,
        supportingText = stringResource(R.string.appeal_message_help),
      )

      PrimaryButton(
        text = stringResource(R.string.appeal_send),
        onClick = {
          sending = true
          scope.launch {
            when (appealsRepo?.submit(me, kind, message.trim())) {
              com.samvad.messenger.data.SubmitAppealResult.Submitted -> {
                message = ""
                snackbar.showSnackbar(msgSent)
              }
              SubmitAppealResult.TooManyToday -> snackbar.showSnackbar(msgTooMany)
              else -> snackbar.showSnackbar(msgFailed)
            }
            sending = false
          }
        },
        enabled = !invalid && !sending,
      )

      if (past.isNotEmpty()) {
        Text(
          stringResource(R.string.appeal_history_title),
          style = MaterialTheme.typography.titleSmall,
          color = colors.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
        past.forEach { appeal -> AppealRow(appeal) }
      }
    }
    SnackbarHost(snackbar, Modifier.padding(16.dp))
  }
}

@Composable
private fun FormGroup(title: String, content: @Composable () -> Unit) {
  val colors = SamvadTheme.colors
  Column {
    Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    Column(
      Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(20.dp)).padding(vertical = 4.dp),
    ) { content() }
  }
}

@Composable
private fun KindRow(label: String, selected: Boolean, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton).padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.primary, unselectedColor = colors.outline))
    Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
  }
}

@Composable
private fun AppealRow(appeal: Appeal) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().background(colors.surface, MaterialTheme.shapes.large).padding(14.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(appealKindLabel(appeal.kind), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
      Text(
        TimeLabels.chatListTime(appeal.createdAtMs, System.currentTimeMillis()),
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
      )
    }
    Text(
      appealStatusLabel(appeal.status),
      style = MaterialTheme.typography.labelMedium,
      color = when (appeal.status) {
        AppealStatus.GRANTED -> colors.primary
        AppealStatus.DENIED -> colors.error
        AppealStatus.OPEN -> colors.onSurfaceVariant
      },
    )
  }
}

@Composable
private fun appealKindLabel(kind: AppealKind): String = stringResource(
  when (kind) {
    AppealKind.REPORT_DECISION -> R.string.appeal_kind_report_decision
    AppealKind.ACCOUNT_RESTRICTION -> R.string.appeal_kind_account_restriction
    AppealKind.OTHER -> R.string.appeal_kind_other
  },
)

@Composable
private fun appealStatusLabel(status: AppealStatus): String = stringResource(
  when (status) {
    AppealStatus.OPEN -> R.string.appeal_status_open
    AppealStatus.GRANTED -> R.string.appeal_status_granted
    AppealStatus.DENIED -> R.string.appeal_status_denied
  },
)
