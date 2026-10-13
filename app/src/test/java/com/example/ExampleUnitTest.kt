package com.example

import android.graphics.Bitmap
import com.example.util.DocumentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleUnitTest {

    @Test
    fun `test DocumentFilter enum values`() {
        val filters = DocumentFilter.values()
        assertEquals(3, filters.size)
        assertEquals(DocumentFilter.ORIGINAL, filters[0])
        assertEquals(DocumentFilter.BLACK_AND_WHITE, filters[1])
        assertEquals(DocumentFilter.GRAYSCALE, filters[2])
    }

    @Test
    fun `test bitmap rotation and cropping`() {
        val source = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        assertNotNull(source)
        assertEquals(100, source.width)
        assertEquals(200, source.height)

        val cropped = com.example.util.ImageCropperUtil.cropBitmap(source, 0.1f, 0.1f, 0.9f, 0.9f)
        assertEquals(80, cropped.width)
        assertEquals(160, cropped.height)

        val rotated = com.example.util.ImageCropperUtil.rotateBitmap(source, 90f)
        assertEquals(200, rotated.width)
        assertEquals(100, rotated.height)
    }

    @Test
    fun `test Screen bottomNavItems initialization and non-null properties`() {
        val items = com.example.ui.navigation.Screen.bottomNavItems
        assertNotNull(items)
        assertEquals(4, items.size)
        items.forEach { screen ->
            assertNotNull("Screen in bottomNavItems must not be null", screen)
            assertNotNull("Screen route must not be null", screen.route)
            assertNotNull("Screen title must not be null", screen.title)
            assertNotNull("Screen selectedIcon must not be null", screen.selectedIcon)
            assertNotNull("Screen unselectedIcon must not be null", screen.unselectedIcon)
        }
    }
}
