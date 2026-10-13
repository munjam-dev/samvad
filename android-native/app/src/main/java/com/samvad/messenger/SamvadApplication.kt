package com.samvad.messenger

import android.app.Application

class SamvadApplication : Application() {
  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = AppContainer(this)
    com.samvad.messenger.push.Notifications.createChannels(this)
  }
}
