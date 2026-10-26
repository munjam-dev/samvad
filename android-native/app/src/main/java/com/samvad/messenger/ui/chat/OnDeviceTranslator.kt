package com.samvad.messenger.ui.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage as MlLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.samvad.core.translate.TranslateFailure
import com.samvad.core.translate.TranslateLanguage
import com.samvad.core.translate.TranslateRules
import com.samvad.core.translate.TranslateState
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.cancellation.CancellationException

/**
 * Translation with Google ML Kit, fully on the device. The message text is never sent to a server.
 * The only network use is the one-time download of a language pack, and that carries no message data.
 */
object OnDeviceTranslator {
  suspend fun translate(
    context: Context,
    text: String,
    target: TranslateLanguage,
    onDownloading: () -> Unit,
  ): TranslateState {
    var detected = false
    var same = false
    try {
      val code = LanguageIdentification.getClient().identifyLanguage(text).await()
      val source = if (code == "und") null else MlLanguage.fromLanguageTag(code)
      detected = source != null
      same = source == target.tag
      TranslateRules.failureFor(offline = false, languageDetected = detected, sameLanguage = same)?.let {
        return TranslateState.Failed(it)
      }
      val options = TranslatorOptions.Builder().setSourceLanguage(source!!).setTargetLanguage(target.tag).build()
      val translator = Translation.getClient(options)
      try {
        val manager = RemoteModelManager.getInstance()
        val needed = listOf(source, target.tag).filter { it != MlLanguage.ENGLISH }
        val missing = needed.any { !manager.isModelDownloaded(TranslateRemoteModel.Builder(it).build()).await() }
        if (missing) onDownloading()
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return TranslateState.Done(translator.translate(text).await())
      } finally {
        translator.close()
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return TranslateState.Failed(if (!isOnline(context)) TranslateFailure.OFFLINE else TranslateFailure.FAILED)
    }
  }

  private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }
}
