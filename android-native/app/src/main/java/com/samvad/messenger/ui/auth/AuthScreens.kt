package com.samvad.messenger.ui.auth

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.auth.AuthError
import com.samvad.core.phone.Country
import com.samvad.core.phone.PhoneNumbers
import com.samvad.core.phone.PhoneValidation
import com.samvad.messenger.AppContainer
import com.samvad.messenger.BuildConfig
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.QuietButton
import com.samvad.messenger.ui.components.SamvadTextField
import com.samvad.messenger.ui.theme.JakartaSans
import com.samvad.messenger.ui.theme.SamvadTheme

/** Sign-in: phone number, then code. The session observer moves on once Firebase signs in. */
@Composable
fun AuthFlow(container: AppContainer) {
  val vm: AuthViewModel = viewModel(factory = AuthViewModel.factory(container.auth!!))
  val state = vm.state.collectAsStateWithLifecycle().value
  val activity = requireNotNull(LocalActivity.current)
  AnimatedContent(state.step, label = "auth") { step ->
    when (step) {
      AuthStep.PHONE -> PhoneStep(state, vm, onSend = { vm.sendCode(activity) })
      AuthStep.CODE, AuthStep.DONE -> OtpStep(
        state = state,
        onCode = vm::onCodeChange,
        onVerify = vm::verify,
        onResend = { vm.sendCode(activity, isResend = true) },
        onBack = vm::backToPhone,
      )
    }
  }
}

@Composable
fun authErrorText(error: AuthError): String = stringResource(
  when (error) {
    AuthError.INVALID_NUMBER -> R.string.error_invalid_number
    AuthError.INVALID_CODE -> R.string.error_invalid_code
    AuthError.CODE_EXPIRED -> R.string.error_code_expired
    AuthError.TOO_MANY_REQUESTS -> R.string.error_too_many
    AuthError.NETWORK -> R.string.error_network
    AuthError.APP_VERIFICATION_FAILED -> R.string.error_app_verification
    AuthError.USER_DISABLED -> R.string.error_user_disabled
    AuthError.NEW_CODE_REQUIRED -> R.string.error_new_code_required
    AuthError.UNKNOWN -> R.string.error_unknown
  },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneStep(state: AuthUiState, vm: AuthViewModel, onSend: () -> Unit) {
  val colors = SamvadTheme.colors
  val uri = LocalUriHandler.current
  var picking by rememberSaveable { mutableStateOf(false) }

  Column(
    Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
  ) {
    Spacer(Modifier.height(48.dp))
    Text(stringResource(R.string.phone_title), style = MaterialTheme.typography.headlineMedium, color = colors.onSurface)
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.phone_body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
    Spacer(Modifier.height(32.dp))

    Text(stringResource(R.string.phone_country), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Row(
      Modifier
        .fillMaxWidth()
        .height(56.dp)
        .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
        .background(colors.surface, RoundedCornerShape(16.dp))
        .clickable { picking = true }
        .padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "${state.country.name} (+${state.country.callingCode})",
        style = MaterialTheme.typography.bodyLarge,
        color = colors.onSurface,
        modifier = Modifier.weight(1f),
      )
      Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = colors.onSurfaceVariant)
    }
    Spacer(Modifier.height(16.dp))

    val v = state.validation
    SamvadTextField(
      value = state.number,
      onValueChange = vm::onNumberChange,
      label = stringResource(R.string.phone_number),
      keyboardType = KeyboardType.Phone,
      isError = v is PhoneValidation.Invalid || v is PhoneValidation.NotMobile,
      supportingText = when (v) {
        is PhoneValidation.Valid -> stringResource(R.string.phone_valid)
        PhoneValidation.Invalid -> stringResource(R.string.phone_invalid)
        PhoneValidation.NotMobile -> stringResource(R.string.phone_not_mobile)
        else -> null
      },
    )

    Spacer(Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.Top) {
      Checkbox(
        checked = state.consent,
        onCheckedChange = vm::onConsent,
        colors = CheckboxDefaults.colors(checkedColor = colors.primary, uncheckedColor = colors.outline),
      )
      Column(Modifier.padding(top = 12.dp)) {
        Text(stringResource(R.string.phone_consent), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Row {
          QuietButton(stringResource(R.string.phone_terms), { uri.openUri(BuildConfig.TERMS_URL) })
          QuietButton(stringResource(R.string.phone_privacy), { uri.openUri(BuildConfig.PRIVACY_URL) })
        }
      }
    }
    state.error?.let { Text(authErrorText(it), color = colors.error, style = MaterialTheme.typography.bodyMedium) }
    if (state.hourlyLimit) Text(stringResource(R.string.error_hourly_limit), color = colors.error, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    PrimaryButton(stringResource(R.string.phone_send_code), onSend, enabled = state.canSend, loading = state.sending)
    Spacer(Modifier.height(24.dp))
  }

  if (picking) {
    ModalBottomSheet(onDismissRequest = { picking = false }) {
      CountryPicker(onPick = { vm.onCountry(it); picking = false })
    }
  }
}

@Composable
private fun CountryPicker(onPick: (Country) -> Unit) {
  val colors = SamvadTheme.colors
  var query by rememberSaveable { mutableStateOf("") }
  val all = remember { PhoneNumbers.countries() }
  val shown = remember(query) {
    all.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || "+${it.callingCode}".contains(query) }
  }
  Column(Modifier.padding(horizontal = 16.dp)) {
    SamvadTextField(query, { query = it }, stringResource(R.string.phone_search_country))
    LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
      items(shown, key = { it.regionCode }) { c ->
        Row(
          Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 14.dp, horizontal = 4.dp),
        ) {
          Text(c.name, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
          Text("+${c.callingCode}", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        }
      }
    }
  }
}

@Composable
fun OtpStep(
  state: AuthUiState,
  onCode: (String) -> Unit,
  onVerify: () -> Unit,
  onResend: () -> Unit,
  onBack: () -> Unit,
) {
  val colors = SamvadTheme.colors
  val focus = remember { FocusRequester() }
  BackHandler(onBack = onBack)
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
  LaunchedEffect(state.code) { if (state.code.length == 6) onVerify() }

  Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 24.dp)) {
    IconButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) {
      Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface)
    }
    Spacer(Modifier.height(20.dp))
    Text(stringResource(R.string.otp_title), style = MaterialTheme.typography.headlineMedium, color = colors.onSurface)
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.otp_sent_to, state.display), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
    QuietButton(stringResource(R.string.otp_wrong_number), onBack)
    Spacer(Modifier.height(20.dp))

    val label = stringResource(R.string.otp_label)
    BasicTextField(
      value = state.code,
      onValueChange = onCode,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
      modifier = Modifier.focusRequester(focus).semantics { contentDescription = label },
      decorationBox = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          repeat(6) { i ->
            val char = state.code.getOrNull(i)?.toString() ?: ""
            val active = i == state.code.length
            Box(
              Modifier
                .weight(1f)
                .height(60.dp)
                .background(colors.surface, RoundedCornerShape(14.dp))
                .border(if (active) 2.dp else 1.dp, if (active) colors.primary else colors.outline, RoundedCornerShape(14.dp)),
              contentAlignment = Alignment.Center,
            ) {
              Text(char, style = TextStyle(fontFamily = JakartaSans, fontSize = 24.sp), color = colors.onSurface, textAlign = TextAlign.Center)
            }
          }
        }
      },
    )
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(Icons.Outlined.Sms, contentDescription = null, tint = colors.brand, modifier = Modifier.size(18.dp))
      Text(stringResource(R.string.otp_auto), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
    state.error?.let {
      Spacer(Modifier.height(12.dp))
      Text(authErrorText(it), color = colors.error, style = MaterialTheme.typography.bodyMedium)
      if (it == AuthError.INVALID_CODE) {
        Text(stringResource(R.string.otp_attempts_left, state.attemptsLeft), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
      }
    }
    Spacer(Modifier.height(24.dp))
    Row(
      Modifier.fillMaxWidth().background(colors.surfaceContainer, RoundedCornerShape(14.dp)).padding(start = 16.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
        Text(stringResource(R.string.otp_didnt_get), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        if (state.resendLimit) {
          Text(stringResource(R.string.error_resend_limit), style = MaterialTheme.typography.bodySmall, color = colors.error)
        } else {
          Text(stringResource(R.string.otp_resends_left, state.resendsLeft), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
      }
      val wait = state.resendInSeconds
      QuietButton(
        if (wait > 0) stringResource(R.string.otp_resend_in, "0:%02d".format(wait)) else stringResource(R.string.otp_resend),
        onResend,
        enabled = wait == 0 && !state.resendLimit && !state.sending,
      )
    }
    Spacer(Modifier.weight(1f))
    PrimaryButton(
      stringResource(R.string.otp_verify),
      onVerify,
      enabled = state.code.length == 6 && state.error != AuthError.NEW_CODE_REQUIRED,
      loading = state.verifying,
      modifier = Modifier.padding(bottom = 24.dp),
    )
    if (state.step == AuthStep.DONE) {
      Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = colors.brand)
    }
  }
}
