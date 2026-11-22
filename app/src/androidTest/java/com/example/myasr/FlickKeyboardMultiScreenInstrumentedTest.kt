package com.example.myasr

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myasr.context.ime.presentation.FlickKeyboardView
import com.example.myasr.context.ime.presentation.FlickPopupGeometry
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class FlickKeyboardMultiScreenInstrumentedTest {

    private data class DeviceProfile(
        val name: String,
        val screenWidthPx: Int,
        val screenHeightPx: Int,
        val densityDpi: Int,
        val keyboardHeightDp: Int
    )

    @Test
    fun verifyAndRenderTwelveKeyFlickAcrossTypicalDeviceScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val baseContext = instrumentation.targetContext
        val outDir = File(baseContext.getExternalFilesDir(null), "screens").apply { mkdirs() }

        val profiles = listOf(
            DeviceProfile("360dp_compact_phone", 1080, 1920, 480, 236),
            DeviceProfile("411dp_standard_phone", 1080, 2400, 420, 248),
            DeviceProfile("480dp_large_phone", 1440, 3120, 480, 260),
            DeviceProfile("701dp_foldable", 1840, 2208, 420, 264),
            DeviceProfile("800dp_tablet_portrait", 1600, 2560, 320, 280),
            DeviceProfile("914dp_phone_landscape", 2400, 1080, 420, 184)
        )

        val keysField = FlickKeyboardView::class.java.getDeclaredField("keys").apply { isAccessible = true }
        var keyRectField: java.lang.reflect.Field? = null

        instrumentation.runOnMainSync {
            for (profile in profiles) {
                val density = profile.densityDpi / 160f
                val config = Configuration(baseContext.resources.configuration).apply {
                    densityDpi = profile.densityDpi
                    screenWidthDp = (profile.screenWidthPx * 160) / profile.densityDpi
                    screenHeightDp = (profile.screenHeightPx * 160) / profile.densityDpi
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
                }
                val configuredContext = baseContext.createConfigurationContext(config)

                // Match keyboard_view.xml keyboardRoot 4dp start + 4dp end padding
                val rootPadPx = (4f * density).toInt()
                val viewWidthPx = profile.screenWidthPx - rootPadPx * 2
                val viewHeightPx = (profile.keyboardHeightDp * density).toInt()

                val keyboardView = FlickKeyboardView(configuredContext).apply {
                    setMode(JapaneseKanaUtils.KeyboardMode.KANA)
                    setComposingState(isComposing = false, canUndo = false, hasVoiceComposing = false)
                    measure(
                        View.MeasureSpec.makeMeasureSpec(viewWidthPx, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(viewHeightPx, View.MeasureSpec.EXACTLY)
                    )
                    layout(0, 0, viewWidthPx, viewHeightPx)
                }

                // Verify computed grid + actual laid-out key RectF widths inside FlickKeyboardView
                val grid = FlickPopupGeometry.computeTwelveKeyGrid(viewWidthPx.toFloat(), viewHeightPx.toFloat(), density)
                @Suppress("UNCHECKED_CAST")
                val laidOutKeys = keysField.get(keyboardView) as List<Any>
                assertEquals("${profile.name} should have 20 keys in KANA mode", 20, laidOutKeys.size)

                val rectField = keyRectField ?: laidOutKeys.first().javaClass.getDeclaredField("rect").apply {
                    isAccessible = true
                    keyRectField = this
                }
                val rects = laidOutKeys.map { rectField.get(it) as RectF }
                val col0Keys = rects.slice(0..3)
                val kanaKeys = rects.slice(4..15)
                val col4Keys = rects.slice(16..19)

                for (r in rects) {
                    assertEquals(
                        "${profile.name}: every key width must match visualKeyWidth",
                        grid.visualKeyWidth,
                        r.width(),
                        0.05f
                    )
                }
                for (r in col0Keys) {
                    assertTrue(
                        "${profile.name}: Col 0 vertical edges must align across all 4 rows",
                        abs(r.left - col0Keys[0].left) < 0.05f && abs(r.right - col0Keys[0].right) < 0.05f
                    )
                }
                for (r in col4Keys) {
                    assertTrue(
                        "${profile.name}: Col 4 vertical edges must align across all 4 rows",
                        abs(r.left - col4Keys[0].left) < 0.05f && abs(r.right - col4Keys[0].right) < 0.05f
                    )
                }
                assertEquals(
                    "${profile.name}: center 12-key width must equal function key width (1:1)",
                    kanaKeys[0].width(),
                    col0Keys[0].width(),
                    0.05f
                )

                // Render FlickKeyboardView onto Bitmap and save PNG for visual verification
                val bitmap = Bitmap.createBitmap(profile.screenWidthPx, viewHeightPx + rootPadPx * 2, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.parseColor("#EEF1F6"))
                canvas.translate(rootPadPx.toFloat(), rootPadPx.toFloat())
                keyboardView.draw(canvas)

                FileOutputStream(File(outDir, "jotinput_${profile.name}.png")).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                bitmap.recycle()
            }
        }
    }
}
