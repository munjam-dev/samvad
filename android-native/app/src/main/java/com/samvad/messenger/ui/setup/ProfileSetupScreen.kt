package com.samvad.messenger.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.samvad.messenger.AppContainer
import com.samvad.core.profile.Usernames
import com.samvad.messenger.R
import com.samvad.messenger.data.UsernameResult
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.QuietButton
import com.samvad.messenger.ui.components.SamvadTextField
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.launch

private const val STEPS = 3

/**
 * One question per screen with a progress bar. Only what the backend stores is asked:
 * display name, an optional username and an optional about line. No gender, birth date or other personal data.
 */
@Composable
fun ProfileSetupScreen(container: AppContainer, uid: String, phone: String, onDone: () -> Unit) {
  val colors = SamvadTheme.colors
  val scope = rememberCoroutineScope()
  var step by rememberSaveable { mutableIntStateOf(1) }
  var name by rememberSaveable { mutableStateOf("") }
  var username by rememberSaveable { mutableStateOf("") }
  var about by rememberSaveable { mutableStateOf("") }
  var created by rememberSaveable { mutableStateOf(false) }
  var lostName by rememberSaveable { mutableStateOf(false) }
  var saving by rememberSaveable { mutableStateOf(false) }
  var failed by rememberSaveable { mutableStateOf(false) }
  val nameOk = name.isNotBlank() && name.length <= 64
  val status by usernameStatus(container.accounts, username, current = null)
  val shown = if (lostName && status == UsernameStatus.AVAILABLE) UsernameStatus.TAKEN else status

  BackHandler(enabled = step > 1) { step-- }

  fun finish() {
    saving = true
    failed = false
    scope.launch {
      runCatching { if (!created) container.accounts!!.createAccount(uid, phone, name.trim(), about.trim()) }
        .onSuccess {
          created = true
          val wanted = Usernames.normalize(username)
          // The name may have been taken since it was checked: send the person back to choose another.
          if (wanted.isNotEmpty() && container.accounts!!.setUsername(wanted) == UsernameResult.Taken) {
            lostName = true
            step = 2
          } else {
            onDone()
          }
        }
        .onFailure { failed = true }
      saving = false
    }
  }

  Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 24.dp)) {
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      val progress by animateFloatAsState(step / STEPS.toFloat(), label = "progress")
      LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
        color = colors.brand,
        trackColor = colors.surfaceContainer,
      )
      Text(
        stringResource(R.string.setup_step, step, STEPS),
        style = MaterialTheme.typography.labelMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp),
      )
    }
    Spacer(Modifier.height(56.dp))
    AnimatedContent(step, label = "setup", modifier = Modifier.weight(1f)) { s ->
      Column(Modifier.fillMaxWidth()) {
        if (s == 1) {
          Text(stringResource(R.string.setup_name_title), style = MaterialTheme.typography.headlineMedium, color = colors.onSurface)
          Spacer(Modifier.height(24.dp))
          SamvadTextField(
            name, { name = it }, stringResource(R.string.setup_name_hint),
            supportingText = stringResource(R.string.setup_name_help), maxLength = 64,
          )
        } else if (s == 2) {
          Text(stringResource(R.string.setup_username_title), style = MaterialTheme.typography.headlineMedium, color = colors.onSurface)
          Spacer(Modifier.height(24.dp))
          UsernameField(username, { username = it; lostName = false }, shown)
        } else {
          Text(stringResource(R.string.setup_about_title), style = MaterialTheme.typography.headlineMedium, color = colors.onSurface)
          Spacer(Modifier.height(24.dp))
          SamvadTextField(
            about, { about = it }, stringResource(R.string.profile_about),
            placeholder = stringResource(R.string.setup_about_hint),
            supportingText = stringResource(R.string.setup_about_help), maxLength = 140, singleLine = false,
          )
        }
      }
    }
    if (failed) Text(stringResource(R.string.error_unknown), color = colors.error, style = MaterialTheme.typography.bodyMedium)
    if (step == 2 && username.isNotEmpty()) {
      QuietButton(stringResource(R.string.setup_skip), { username = ""; step++ }, Modifier.align(Alignment.CenterHorizontally))
    }
    if (step == 3) {
      QuietButton(stringResource(R.string.setup_skip), { about = ""; finish() }, Modifier.align(Alignment.CenterHorizontally), enabled = !saving)
    }
    PrimaryButton(
      text = stringResource(if (step == STEPS) R.string.setup_finish else R.string.onboarding_next),
      onClick = { if (step < STEPS) step++ else finish() },
      enabled = nameOk && (step != 2 || shown.ok),
      loading = saving,
      modifier = Modifier.padding(bottom = 24.dp, top = 8.dp),
    )
  }
}
