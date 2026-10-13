package com.samvad.messenger

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SamvadApplication : Application() {
  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = AppContainer(this)
    com.samvad.messenger.push.Notifications.createChannels(this)
    // Online while the app is visible; offline (with last seen) when it goes to the background.
    // The same moments drive the app lock timer.
    ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
      override fun onStart(owner: LifecycleOwner) {
        container.appLock.onForeground()
        val uid = container.auth?.currentUser?.uid ?: return
        owner.lifecycleScope.launch { container.presence?.goOnline(uid) }
      }

      override fun onStop(owner: LifecycleOwner) {
        container.appLock.onBackground()
        val uid = container.auth?.currentUser?.uid ?: return
        owner.lifecycleScope.launch { container.presence?.goOffline(uid) }
      }
    })
  }
}
