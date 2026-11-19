package com.example.myasr

import android.content.Intent
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Local JVM launch & UI inflation tests using Robolectric.
 * Catches Activity/Application startup crashes (e.g., UninitializedPropertyAccessException,
 * InflateException, Resources.NotFoundException, Slider/Material theme errors) in
 * `./gradlew testDebugUnitTest` without needing an emulator or physical device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AsrApp::class)
class AppLaunchRobolectricTest {

    @Test
    fun `MainActivity launches cleanly and switches all 3 tabs`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()

        val activity = controller.get()
        assertNotNull(activity)

        val bottomNav = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
        assertNotNull(bottomNav)

        bottomNav.selectedItemId = R.id.nav_settings
        bottomNav.selectedItemId = R.id.nav_data
        bottomNav.selectedItemId = R.id.nav_home

        controller.pause().stop().destroy()
    }

    @Test
    fun `OnboardingActivity launches cleanly and steps through wizard`() {
        val intent = Intent().apply {
            putExtra(OnboardingActivity.EXTRA_FORCE_LAUNCH, true)
        }
        val controller = Robolectric.buildActivity(OnboardingActivity::class.java, intent)
            .create()
            .start()
            .resume()
            .visible()

        val activity = controller.get()
        assertNotNull(activity)

        val btnNext = activity.findViewById<android.view.View>(R.id.btnObNext)
        assertNotNull(btnNext)
        // Step 0 (Welcome) -> Step 1 (Data Prep)
        btnNext.performClick()

        controller.pause().stop().destroy()
    }

    @Test
    fun `DictionaryActivity launches cleanly`() {
        val controller = Robolectric.buildActivity(DictionaryActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()

        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }
}
