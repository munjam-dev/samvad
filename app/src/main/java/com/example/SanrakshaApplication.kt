package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.data.SanrakshaDatabase
import com.example.repository.DocumentVaultRepository
import com.example.security.CryptoVaultManager

class SanrakshaApplication : Application() {

    lateinit var database: SanrakshaDatabase
        private set

    lateinit var cryptoVaultManager: CryptoVaultManager
        private set

    lateinit var repository: DocumentVaultRepository
        private set

    override fun onCreate() {
        super.onCreate()
        cryptoVaultManager = CryptoVaultManager(this)
        database = SanrakshaDatabase.getDatabase(this)
        repository = DocumentVaultRepository(this, database, cryptoVaultManager)

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_EXPIRY_REMINDERS,
                "Document Expiry Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Proactive offline alerts for documents approaching expiry"
                enableLights(true)
                enableVibration(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_EXPIRY_REMINDERS = "sanraksha_reminders_channel"
    }
}
