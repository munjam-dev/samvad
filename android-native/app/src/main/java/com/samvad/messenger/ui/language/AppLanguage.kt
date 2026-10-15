package com.samvad.messenger.ui.language

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.samvad.core.profile.Languages
import java.util.Locale

/**
 * The language the app is shown in. On Android 13 and later the system keeps it (so the phone's own per-app
 * language setting and this picker agree); before that it is kept here and applied when an activity starts.
 */
object AppLanguage {
  private const val PREFS = "samvad_language"
  private const val KEY = "tag"

  /** The supported language tag currently in effect. */
  fun current(context: Context): String {
    val chosen = if (Build.VERSION.SDK_INT >= 33) {
      context.getSystemService(LocaleManager::class.java).applicationLocales.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag()
    } else {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    }
    return Languages.resolve(chosen ?: Locale.getDefault().toLanguageTag()).tag
  }

  /** Switches the app to [tag]. The activity is recreated so every screen picks it up. */
  fun apply(activity: Activity, tag: String) {
    if (Build.VERSION.SDK_INT >= 33) {
      activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
    } else {
      activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
      activity.recreate()
    }
  }

  /** For `attachBaseContext` on Android 12 and earlier, where the system does not apply the choice for us. */
  fun wrap(base: Context): Context {
    if (Build.VERSION.SDK_INT >= 33) return base
    val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return base
    val locale = Locale.forLanguageTag(tag)
    Locale.setDefault(locale)
    val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
    return base.createConfigurationContext(config)
  }
}
