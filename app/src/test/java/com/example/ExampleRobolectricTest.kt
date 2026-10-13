package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.reminder.ReminderScheduler
import com.example.security.PreferencesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Sanraksha", appName)
    }

    @Test
    fun `verify preferences manager pin hashing and validation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferencesManager(context)

        assertFalse(prefs.hasPin())
        prefs.setPin("1234")
        assertTrue(prefs.hasPin())
        assertTrue(prefs.verifyPin("1234"))
        assertFalse(prefs.verifyPin("9999"))
    }

    @Test
    fun `verify reminder timestamp calculation for future expiry`() {
        val futureExpiry = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(45)
        val intervals = listOf(30, 7, 1)

        val scheduled = ReminderScheduler.calculateReminderTimestamps(futureExpiry, intervals)
        assertEquals(3, scheduled.size)
        assertEquals(30, scheduled[0].first)
        assertEquals(7, scheduled[1].first)
        assertEquals(1, scheduled[2].first)
    }
}
