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

    private fun measureInkRatio(
        bitmap: Bitmap,
        rect: RectF,
        offsetPx: Float
    ): Pair<Float, Float> {
        val kh = rect.height()
        val kw = rect.width()
        val padX = (kw * 0.10f).toInt().coerceAtLeast(3)
        val padY = (kh * 0.10f).toInt().coerceAtLeast(3)
        val x0 = (rect.left + offsetPx).toInt() + padX
        val x1 = (rect.right + offsetPx).toInt() - padX
        val y0 = (rect.top + offsetPx).toInt() + padY
        val y1 = (rect.bottom + offsetPx).toInt() - padY
        val bg = bitmap.getPixel(x0, y0)
        val bgR = Color.red(bg)
        val bgG = Color.green(bg)
        val bgB = Color.blue(bg)
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (y in y0..y1) {
            for (x in x0..x1) {
                val p = bitmap.getPixel(x, y)
                val diff = abs(Color.red(p) - bgR) + abs(Color.green(p) - bgG) + abs(Color.blue(p) - bgB)
                if (diff > 90) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return 0f to 0f
        return (maxX - minX + 1) / kh to (maxY - minY + 1) / kh
    }

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

                // Verify actual rendered ink proportions for Kana and key function icons (Undo, Delete, Space, Enter)
                val (undoW, undoH) = measureInkRatio(bitmap, rects[0], rootPadPx.toFloat())
                val (_, kanaH) = measureInkRatio(bitmap, rects[4], rootPadPx.toFloat())
                val (puncW, _) = measureInkRatio(bitmap, rects[15], rootPadPx.toFloat())
                val (delW, delH) = measureInkRatio(bitmap, rects[16], rootPadPx.toFloat())
                val (spaceW, spaceH) = measureInkRatio(bitmap, rects[18], rootPadPx.toFloat())
                val (enterW, enterH) = measureInkRatio(bitmap, rects[19], rootPadPx.toFloat())

                assertTrue("${profile.name} Kana 'あ' h/kh=$kanaH too small", kanaH >= 0.36f)
                assertTrue("${profile.name} Undo '↶' w/kh=$undoW h/kh=$undoH too small", undoW >= 0.22f && undoH >= 0.20f)
                assertTrue("${profile.name} Del '⌫' w/kh=$delW h/kh=$delH too small", delW >= 0.34f && delH >= 0.25f)
                assertTrue("${profile.name} Space '␣' w/kh=$spaceW h/kh=$spaceH too small", spaceW >= 0.24f && spaceH >= 0.08f)
                assertTrue("${profile.name} Enter '↵' w/kh=$enterW h/kh=$enterH too small", enterW >= 0.26f && enterH >= 0.18f)
                assertTrue("${profile.name} Punc '、。?!' w/kh=$puncW stretched too wide", puncW <= 0.72f)

                bitmap.recycle()

                if (profile.name == "411dp_standard_phone") {
                    keyboardView.setComposingState(isComposing = true, canUndo = true, hasVoiceComposing = false)
                    val compBitmap = Bitmap.createBitmap(profile.screenWidthPx, viewHeightPx + rootPadPx * 2, Bitmap.Config.ARGB_8888)
                    val compCanvas = Canvas(compBitmap)
                    compCanvas.drawColor(Color.parseColor("#EEF1F6"))
                    compCanvas.translate(rootPadPx.toFloat(), rootPadPx.toFloat())
                    keyboardView.draw(compCanvas)
                    FileOutputStream(File(outDir, "jotinput_${profile.name}_composing.png")).use { fos ->
                        compBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    compBitmap.recycle()
                }
            }
        }
    }
}
