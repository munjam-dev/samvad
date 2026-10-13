package com.samvad.messenger

import android.content.Context
import com.samvad.messenger.data.AccountRepository
import com.samvad.messenger.data.AuthRepository
import com.samvad.messenger.data.ChatRepository
import com.samvad.messenger.data.ContactsRepository
import com.samvad.messenger.data.FirebaseServices
import com.samvad.messenger.data.PreferencesRepository

/**
 * Manual dependency container. A DI framework (Hilt) is deferred until the build can be verified
 * with the Android SDK; this keeps the object graph explicit and easy to replace in tests.
 */
class AppContainer(context: Context) {
  val preferences = PreferencesRepository(context)
  val firebase: FirebaseServices? = FirebaseServices.create(context)

  val auth: AuthRepository? = firebase?.let { AuthRepository(it.auth) }
  val accounts: AccountRepository? = firebase?.let { AccountRepository(it.db, it.functions) }
  val chats: ChatRepository? = firebase?.let { ChatRepository(it.db) }
  val contacts: ContactsRepository? = firebase?.let { ContactsRepository(context, it.functions) }

  val isConfigured: Boolean get() = firebase != null
}
