package com.samvad.messenger.ui.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.samvad.core.profile.Usernames
import com.samvad.core.profile.Usernames.Problem
import com.samvad.messenger.R
import com.samvad.messenger.data.AccountRepository
import com.samvad.messenger.data.UsernameCheck
import com.samvad.messenger.ui.components.SamvadTextField
import kotlinx.coroutines.delay

/** What to tell the person about the username they typed. */
enum class UsernameStatus(val ok: Boolean) {
  /** Nothing typed: a username is optional. */
  EMPTY(true),
  CHECKING(false),
  AVAILABLE(true),
  /** The name is the one the account already has. */
  CURRENT(true),
  TAKEN(false),
  TOO_SHORT(false), TOO_LONG(false), BAD_START(false), BAD_CHARS(false), UNDERSCORES(false), RESERVED(false),
  /** The server could not be asked (offline, error): the name is not known to be free, so it cannot be saved yet. */
  UNKNOWN(false),
  TOO_MANY_CHECKS(false),
}

private const val DEBOUNCE_MS = 450L

/**
 * Checks [input] while it is typed: the format on the device straight away, then the server (after a short pause, so
 * a fast typist sends one request, not one per letter). [current] is the name the account already has, if any.
 */
@Composable
fun usernameStatus(accounts: AccountRepository?, input: String, current: String?): State<UsernameStatus> =
  produceState(if (input.isEmpty()) UsernameStatus.EMPTY else UsernameStatus.CHECKING, input, current) {
    val name = Usernames.normalize(input)
    value = when {
      name.isEmpty() -> UsernameStatus.EMPTY
      name == current -> UsernameStatus.CURRENT
      else -> when (Usernames.problem(name)) {
        Problem.TOO_SHORT -> UsernameStatus.TOO_SHORT
        Problem.TOO_LONG -> UsernameStatus.TOO_LONG
        Problem.BAD_START -> UsernameStatus.BAD_START
        Problem.BAD_CHARS -> UsernameStatus.BAD_CHARS
        Problem.UNDERSCORES -> UsernameStatus.UNDERSCORES
        Problem.RESERVED -> UsernameStatus.RESERVED
        null -> {
          value = UsernameStatus.CHECKING
          delay(DEBOUNCE_MS)
          when (val r = accounts?.checkUsername(name)) {
            UsernameCheck.Available -> UsernameStatus.AVAILABLE
            is UsernameCheck.Unavailable -> if (r.reason == "taken") UsernameStatus.TAKEN else UsernameStatus.RESERVED
            UsernameCheck.TooManyChecks -> UsernameStatus.TOO_MANY_CHECKS
            else -> UsernameStatus.UNKNOWN
          }
        }
      }
    }
  }

@Composable
private fun message(status: UsernameStatus): String = when (status) {
  UsernameStatus.EMPTY -> stringResource(R.string.username_help)
  UsernameStatus.CHECKING -> stringResource(R.string.username_checking)
  UsernameStatus.AVAILABLE -> stringResource(R.string.username_available)
  UsernameStatus.CURRENT -> stringResource(R.string.username_current)
  UsernameStatus.TAKEN -> stringResource(R.string.username_taken)
  UsernameStatus.TOO_SHORT -> stringResource(R.string.username_too_short, Usernames.MIN_LENGTH)
  UsernameStatus.TOO_LONG -> stringResource(R.string.username_too_long, Usernames.MAX_LENGTH)
  UsernameStatus.BAD_START -> stringResource(R.string.username_bad_start)
  UsernameStatus.BAD_CHARS -> stringResource(R.string.username_bad_chars)
  UsernameStatus.UNDERSCORES -> stringResource(R.string.username_underscores)
  UsernameStatus.RESERVED -> stringResource(R.string.username_reserved)
  UsernameStatus.UNKNOWN -> stringResource(R.string.username_unknown)
  UsernameStatus.TOO_MANY_CHECKS -> stringResource(R.string.username_too_many_checks)
}

/** The username input with its live feedback line. The text is kept lower case, as usernames are not case sensitive. */
@Composable
fun UsernameField(value: String, onValueChange: (String) -> Unit, status: UsernameStatus, modifier: Modifier = Modifier) {
  val problem = !status.ok && status != UsernameStatus.CHECKING && status != UsernameStatus.EMPTY
  SamvadTextField(
    value = value,
    onValueChange = { onValueChange(it.lowercase().replace(" ", "")) },
    label = stringResource(R.string.profile_username),
    modifier = modifier.fillMaxWidth(),
    placeholder = stringResource(R.string.username_hint),
    supportingText = message(status),
    isError = problem,
    keyboardType = KeyboardType.Ascii,
    maxLength = Usernames.MAX_LENGTH + 1,
  )
}
