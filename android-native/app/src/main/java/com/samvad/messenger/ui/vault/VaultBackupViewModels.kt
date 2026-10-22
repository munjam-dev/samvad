package com.samvad.messenger.ui.vault

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.vault.ArchiveKdf
import com.samvad.core.vault.BackupException
import com.samvad.core.vault.BackupFailure
import com.samvad.core.vault.BackupPassphrase
import com.samvad.core.vault.RecoveryCode
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.data.vault.backup.BackupSetupDraft
import com.samvad.messenger.data.vault.backup.DriveAuth
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** The words for a backup problem. Nothing here names a file, an item or a key. */
@StringRes
internal fun BackupFailure.messageRes(): Int = when (this) {
  BackupFailure.NO_NETWORK -> R.string.vbk_err_no_network
  BackupFailure.QUOTA_FULL -> R.string.vbk_err_quota
  BackupFailure.AUTH_REVOKED -> R.string.vbk_err_auth
  BackupFailure.NOT_CONFIGURED -> R.string.vbk_err_not_configured
  BackupFailure.TOO_NEW -> R.string.vbk_err_too_new
  BackupFailure.WRONG_SECRET -> R.string.vbk_err_wrong_secret
  BackupFailure.NO_BACKUP -> R.string.vbk_err_no_backup
  BackupFailure.NO_SPACE -> R.string.vbk_err_no_space
  BackupFailure.VAULT_LOCKED -> R.string.vbk_err_locked
  BackupFailure.VAULT_CHANGED -> R.string.vbk_err_changed
  BackupFailure.DAMAGED -> R.string.vbk_err_damaged
  BackupFailure.TRANSIENT -> R.string.vbk_err_transient
  BackupFailure.FAILED -> R.string.vbk_err_failed
}

/**
 * Asking Google for access to the hidden app-data folder. Google may need to show its own screen the first time
 * (or after the person removed access); this class hands the screen to the UI and carries on afterwards.
 */
internal abstract class DriveAccessViewModel(protected val container: AppContainer) : ViewModel() {
  private val _consent = Channel<PendingIntent>(Channel.BUFFERED)

  /** Google's consent screens to launch. The UI reports back with [onConsentResult]. */
  val consent: Flow<PendingIntent> = _consent.receiveAsFlow()

  private var afterAccess: ((String?) -> Unit)? = null

  protected abstract fun onAccessFailed(failure: BackupFailure)

  protected abstract fun onAccessCancelled()

  /** Runs [then] with the person's Google account once access is granted. */
  protected fun withAccess(then: (String?) -> Unit) {
    afterAccess = then
    viewModelScope.launch {
      try {
        when (val r = container.vaultBackup.authorizer.authorize()) {
          is DriveAuth.Granted -> grant(r.email)
          is DriveAuth.NeedsConsent -> _consent.send(r.pendingIntent)
        }
      } catch (e: BackupException) {
        afterAccess = null
        onAccessFailed(e.failure)
      }
    }
  }

  fun onConsentResult(ok: Boolean, data: Intent?) {
    if (!ok) {
      afterAccess = null
      onAccessCancelled()
      return
    }
    try {
      when (val r = container.vaultBackup.authorizer.fromResult(data)) {
        is DriveAuth.Granted -> grant(r.email)
        is DriveAuth.NeedsConsent -> {
          afterAccess = null
          onAccessFailed(BackupFailure.AUTH_REVOKED)
        }
      }
    } catch (e: BackupException) {
      afterAccess = null
      onAccessFailed(e.failure)
    }
  }

  private fun grant(email: String?) {
    val next = afterAccess
    afterAccess = null
    next?.invoke(email)
  }
}

/** One thing the backup screen tells the person once. */
internal sealed interface BackupNote {
  class Text(@param:StringRes val res: Int) : BackupNote

  class Restored(val count: Int) : BackupNote
}

internal sealed interface RestoreUi {
  data object Hidden : RestoreUi

  /** Waiting for the recovery secret. [error] is a string resource when the last try failed. */
  data class Asking(@param:StringRes val error: Int? = null) : RestoreUi

  data object Working : RestoreUi
}

internal sealed interface TurnOffUi {
  data object Hidden : TurnOffUi

  data object Confirm : TurnOffUi

  /** Deleting from Drive failed; offer to turn off on this phone anyway. */
  data class Failed(@param:StringRes val reason: Int) : TurnOffUi
}

internal class VaultBackupViewModel(container: AppContainer) : DriveAccessViewModel(container) {
  private val manager = container.vaultBackup
  val status = manager.status

  private val _notes = Channel<BackupNote>(Channel.BUFFERED)
  val notes: Flow<BackupNote> = _notes.receiveAsFlow()

  private val _restore = MutableStateFlow<RestoreUi>(RestoreUi.Hidden)
  val restore: StateFlow<RestoreUi> = _restore.asStateFlow()

  private val _turnOff = MutableStateFlow<TurnOffUi>(TurnOffUi.Hidden)
  val turnOff: StateFlow<TurnOffUi> = _turnOff.asStateFlow()

  fun backUpNow() {
    withAccess { email ->
      email?.let { manager.onReauthorized(it) }
      manager.startBackup()
    }
  }

  fun reconnect() {
    withAccess { email ->
      manager.onReauthorized(email)
      _notes.trySend(BackupNote.Text(R.string.vbk_connected))
      manager.startBackup()
    }
  }

  fun setSchedule(auto: Boolean, wifiOnly: Boolean, chargingOnly: Boolean) = manager.setSchedule(auto, wifiOnly, chargingOnly)

  // ---- restore ----

  fun askRestore() {
    _restore.value = RestoreUi.Asking()
  }

  fun cancelRestore() {
    if (_restore.value is RestoreUi.Working) return
    _restore.value = RestoreUi.Hidden
    manager.discardDownload()
  }

  fun restore(secret: String) {
    _restore.value = RestoreUi.Working
    withAccess { runRestore(secret) }
  }

  private fun runRestore(secret: String) {
    viewModelScope.launch {
      try {
        val result = manager.restore(secret.trim())
        _restore.value = RestoreUi.Hidden
        _notes.send(if (result.restored > 0) BackupNote.Restored(result.restored) else BackupNote.Text(R.string.vbk_restored_nothing))
      } catch (e: BackupException) {
        if (e.failure == BackupFailure.WRONG_SECRET) {
          _restore.value = RestoreUi.Asking(e.failure.messageRes())
        } else {
          _restore.value = RestoreUi.Hidden
          _notes.send(BackupNote.Text(e.failure.messageRes()))
        }
      }
    }
  }

  // ---- turn off ----

  fun askTurnOff() {
    _turnOff.value = TurnOffUi.Confirm
  }

  fun cancelTurnOff() {
    _turnOff.value = TurnOffUi.Hidden
  }

  fun turnOff(deleteFromDrive: Boolean) {
    _turnOff.value = TurnOffUi.Hidden
    if (deleteFromDrive) withAccess { runTurnOff(true) } else runTurnOff(false)
  }

  private fun runTurnOff(deleteFromDrive: Boolean) {
    viewModelScope.launch {
      try {
        manager.turnOff(deleteFromDrive)
        _notes.send(BackupNote.Text(if (deleteFromDrive) R.string.vbk_off_done else R.string.vbk_off_local_done))
      } catch (e: BackupException) {
        _turnOff.value = TurnOffUi.Failed(e.failure.messageRes())
      }
    }
  }

  override fun onAccessFailed(failure: BackupFailure) {
    // The person asked for restore or turn-off and Google would not give access.
    if (_restore.value == RestoreUi.Working) _restore.value = RestoreUi.Hidden
    _notes.trySend(BackupNote.Text(failure.messageRes()))
  }

  override fun onAccessCancelled() {
    if (_restore.value == RestoreUi.Working) _restore.value = RestoreUi.Asking()
  }

  companion object {
    fun factory(container: AppContainer) = viewModelFactory { initializer { VaultBackupViewModel(container) } }
  }
}

internal enum class SetupStep { EXPLAIN, METHOD, SECRET, CONFIRM, ENABLING }

internal data class SetupState(
  val step: SetupStep = SetupStep.EXPLAIN,
  val method: ArchiveKdf = ArchiveKdf.RECOVERY_KEY,
  /** Waiting for Google. */
  val working: Boolean = false,
  /** Google does not know this build of the app (the owner has not set up the OAuth client yet). */
  val unavailable: Boolean = false,
  /** The recovery code to show; null for a passphrase. */
  val code: String? = null,
  /** 0-based positions of the groups to type back. */
  val challenge: List<Int> = emptyList(),
  @param:StringRes val error: Int? = null,
)

internal class VaultBackupSetupViewModel(container: AppContainer) : DriveAccessViewModel(container) {
  private val manager = container.vaultBackup
  private val _state = MutableStateFlow(SetupState())
  val state: StateFlow<SetupState> = _state.asStateFlow()

  private val _done = Channel<Unit>(Channel.BUFFERED)
  val done: Flow<Unit> = _done.receiveAsFlow()

  private var draft: BackupSetupDraft? = null
  private var email: String? = null

  fun allowAccess() {
    _state.value = _state.value.copy(working = true, error = null)
    withAccess { account ->
      email = account
      _state.value = _state.value.copy(working = false, step = SetupStep.METHOD)
    }
  }

  fun chooseMethod(method: ArchiveKdf) {
    _state.value = _state.value.copy(method = method)
  }

  /** From the method choice: a recovery code is made now; a passphrase is asked for next. */
  fun continueFromMethod() {
    val method = _state.value.method
    if (method == ArchiveKdf.PASSPHRASE_PBKDF2) {
      _state.value = _state.value.copy(step = SetupStep.SECRET, code = null, error = null)
      return
    }
    viewModelScope.launch {
      val d = draft?.takeIf { it.method == ArchiveKdf.RECOVERY_KEY } ?: manager.newRecoveryKeyDraft().also { replaceDraft(it) }
      _state.value = _state.value.copy(step = SetupStep.SECRET, code = d.recoveryCode, error = null)
    }
  }

  fun codeSaved() {
    _state.value = _state.value.copy(step = SetupStep.CONFIRM, challenge = RecoveryCode.challenge(3), error = null)
  }

  fun showCodeAgain() {
    _state.value = _state.value.copy(step = SetupStep.SECRET, error = null)
  }

  /** [typed] maps a 0-based group position to what the person typed. */
  fun confirmCode(typed: Map<Int, String>) {
    val d = draft ?: return
    val code = d.recoveryCode ?: return
    val key = RecoveryCode.parse(code) ?: return
    val ok = RecoveryCode.checkGroups(key, typed)
    key.fill(0)
    if (!ok) {
      _state.value = _state.value.copy(error = R.string.vbk_s4_wrong)
      return
    }
    enable(d)
  }

  fun enableWithPassphrase(passphrase: String) {
    if (!BackupPassphrase.isAcceptable(passphrase)) return
    _state.value = _state.value.copy(step = SetupStep.ENABLING, error = null)
    viewModelScope.launch {
      val d = manager.newPassphraseDraft(passphrase)
      replaceDraft(d)
      enable(d, fromPassphrase = true)
    }
  }

  private fun enable(d: BackupSetupDraft, fromPassphrase: Boolean = false) {
    _state.value = _state.value.copy(step = SetupStep.ENABLING, error = null)
    viewModelScope.launch {
      try {
        manager.enable(d, email)
        draft = null
        _done.send(Unit)
      } catch (e: BackupException) {
        // Stay on the step the person was on, with the reason.
        _state.value = _state.value.copy(
          step = if (fromPassphrase) SetupStep.SECRET else SetupStep.CONFIRM,
          error = e.failure.messageRes(),
        )
      }
    }
  }

  fun back(): Boolean {
    val s = _state.value
    val previous = when (s.step) {
      SetupStep.EXPLAIN, SetupStep.ENABLING -> return false
      SetupStep.METHOD -> SetupStep.EXPLAIN
      SetupStep.SECRET -> SetupStep.METHOD
      SetupStep.CONFIRM -> SetupStep.SECRET
    }
    _state.value = s.copy(step = previous, error = null)
    return true
  }

  private fun replaceDraft(new: BackupSetupDraft) {
    draft?.wipe()
    draft = new
  }

  override fun onAccessFailed(failure: BackupFailure) {
    _state.value = _state.value.copy(
      working = false,
      unavailable = failure == BackupFailure.NOT_CONFIGURED,
      error = if (failure == BackupFailure.NOT_CONFIGURED) null else failure.messageRes(),
    )
  }

  override fun onAccessCancelled() {
    _state.value = _state.value.copy(working = false)
  }

  override fun onCleared() {
    draft?.wipe()
    draft = null
  }

  companion object {
    fun factory(container: AppContainer) = viewModelFactory { initializer { VaultBackupSetupViewModel(container) } }
  }
}
