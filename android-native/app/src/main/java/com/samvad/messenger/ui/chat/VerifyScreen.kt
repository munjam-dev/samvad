package com.samvad.messenger.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.samvad.core.e2ee.SafetyNumber
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.QuietButton
import com.samvad.messenger.ui.theme.SamvadTheme
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ScanResult { MATCH, MISMATCH, UNREADABLE }

data class VerifyState(
  val loading: Boolean = true,
  val name: String = "",
  /** The number of the key in force; null until a session exists. */
  val number: SafetyNumber? = null,
  /** The number of a NEW key waiting for a decision. When set, [number] is not shown. */
  val pendingNumber: SafetyNumber? = null,
  val verified: Boolean = false,
  val scan: ScanResult? = null,
  val qr: Bitmap? = null,
)

/**
 * Safety number screen. Pattern: Detail (vectorlab-ux-skills:page-patterns): one record, the security code of one
 * chat. Shows the 60-digit number from libsignal's fingerprint API and a QR code of the same fingerprint, lets the
 * person scan the other phone's code, and records the result locally. When the other person's key changed, it shows
 * the NEW number and the single decision that takes the chat out of its held state.
 */
class VerifyViewModel(private val container: AppContainer, private val me: String, private val other: String) : ViewModel() {
  private val e2ee = container.e2ee
  private val _state = MutableStateFlow(VerifyState())
  val state: StateFlow<VerifyState> = _state

  init {
    viewModelScope.launch { refresh() }
  }

  private suspend fun refresh() {
    val service = e2ee ?: run { _state.value = VerifyState(loading = false); return }
    runCatching { container.chats?.loadProfiles(listOf(other)) }
    val trust = runCatching { service.trust(me, other) }.getOrNull()
    val pending = trust?.changePending == true
    val number = runCatching { service.safetyNumber(me, other, forPendingKey = pending) }.getOrNull()
    val qr = number?.let { withContext(Dispatchers.Default) { runCatching { qrBitmap(QR_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(it.qrBytes())) }.getOrNull() } }
    _state.value = VerifyState(
      loading = false,
      name = container.chats?.profiles?.value?.get(other)?.displayName.orEmpty(),
      number = if (pending) null else number,
      pendingNumber = if (pending) number else null,
      verified = trust?.verified == true,
      scan = _state.value.scan,
      qr = qr,
    )
  }

  fun setVerified(value: Boolean) {
    viewModelScope.launch {
      e2ee?.setVerified(me, other, value)
      refresh()
    }
  }

  /** "Continue with the new code": trusts the new key, clears the verified mark, and releases held messages. */
  fun acceptChange() {
    viewModelScope.launch {
      e2ee?.acceptIdentityChange(me, other)
      refresh()
    }
  }

  fun onScanned(content: String?) {
    val number = _state.value.number ?: _state.value.pendingNumber
    val bytes = content?.takeIf { it.startsWith(QR_PREFIX) }?.removePrefix(QR_PREFIX)
      ?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }
    val result = when {
      number == null || bytes == null -> ScanResult.UNREADABLE
      number.matchesScan(bytes) -> ScanResult.MATCH
      else -> ScanResult.MISMATCH
    }
    _state.value = _state.value.copy(scan = result)
    if (result == ScanResult.MATCH && _state.value.number != null) setVerified(true)
  }

  private fun qrBitmap(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, QR_PX, QR_PX, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(QR_PX * QR_PX) { i -> if (matrix.get(i % QR_PX, i / QR_PX)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, QR_PX, QR_PX, Bitmap.Config.ARGB_8888)
  }

  companion object {
    private const val QR_PREFIX = "samvad-verify:1:"
    private const val QR_PX = 480

    fun factory(container: AppContainer, me: String, other: String) =
      viewModelFactory { initializer { VerifyViewModel(container, me, other) } }
  }
}

@Composable
fun VerifyScreen(container: AppContainer, me: String, other: String, onBack: () -> Unit) {
  val colors = SamvadTheme.colors
  val vm: VerifyViewModel = viewModel(key = "verify:$other", factory = VerifyViewModel.factory(container, me, other))
  val state by vm.state.collectAsStateWithLifecycle()
  val context = LocalContext.current

  Column(Modifier.fillMaxSize().background(colors.background)) {
    Row(Modifier.statusBarsPadding().padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
      Text(stringResource(R.string.e2ee_verify_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
    }
    when {
      state.loading -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = colors.brand)
      }
      else -> Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        val shown = state.pendingNumber ?: state.number
        if (state.pendingNumber != null) {
          Column(
            Modifier.fillMaxWidth().background(colors.tertiaryContainer, RoundedCornerShape(14.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(stringResource(R.string.e2ee_code_changed_title), style = MaterialTheme.typography.titleSmall, color = colors.onTertiaryContainer)
            Text(stringResource(R.string.e2ee_verify_changed_body, state.name), style = MaterialTheme.typography.bodyMedium, color = colors.onTertiaryContainer)
          }
        }
        if (shown == null) {
          Text(stringResource(R.string.e2ee_verify_none), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
        } else {
          Text(
            stringResource(if (state.pendingNumber != null) R.string.e2ee_verify_new_intro else R.string.e2ee_verify_intro, state.name),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
          )
          state.qr?.let {
            Image(
              it.asImageBitmap(),
              contentDescription = stringResource(R.string.e2ee_verify_qr_cd),
              modifier = Modifier.size(220.dp).background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp)).padding(8.dp),
            )
          }
          // 12 groups of 5 digits in three rows of four, so it can be read aloud and compared at a glance.
          val groups = remember(shown) { shown.grouped() }
          Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            groups.chunked(4).forEach { row ->
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { g -> Text(g, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace), color = colors.onSurface) }
              }
            }
          }
          state.scan?.let { result ->
            Text(
              stringResource(
                when (result) {
                  ScanResult.MATCH -> R.string.e2ee_verify_scan_match
                  ScanResult.MISMATCH -> R.string.e2ee_verify_scan_mismatch
                  ScanResult.UNREADABLE -> R.string.e2ee_verify_scan_unreadable
                },
              ),
              style = MaterialTheme.typography.bodyMedium,
              color = if (result == ScanResult.MATCH) colors.primary else colors.error,
              textAlign = TextAlign.Center,
            )
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.QrCodeScanner, null, tint = colors.primary, modifier = Modifier.size(20.dp))
            QuietButton(stringResource(R.string.e2ee_verify_scan), {
              container.appLock.expectExternal()
              GmsBarcodeScanning.getClient(
                context,
                GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
              ).startScan()
                .addOnSuccessListener { vm.onScanned(it.rawValue) }
                .addOnFailureListener { vm.onScanned(null) }
            })
          }
          if (state.pendingNumber != null) {
            PrimaryButton(stringResource(R.string.e2ee_verify_accept), vm::acceptChange)
            Text(stringResource(R.string.e2ee_verify_accept_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
          } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              if (state.verified) Icon(Icons.Outlined.VerifiedUser, null, tint = colors.primary, modifier = Modifier.size(20.dp))
              Text(
                stringResource(if (state.verified) R.string.e2ee_verify_status_done else R.string.e2ee_verify_status_not),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
              )
            }
            PrimaryButton(
              stringResource(if (state.verified) R.string.e2ee_verify_unmark else R.string.e2ee_verify_mark),
              { vm.setVerified(!state.verified) },
            )
          }
        }
        Text(stringResource(R.string.e2ee_verify_footer), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 24.dp))
      }
    }
  }
}
