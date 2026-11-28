package com.example.myasr

import com.example.myasr.context.kanakanji.infrastructure.MmapDictionaryCompiler
import com.example.myasr.context.provisioning.infrastructure.DictionaryType
import com.example.myasr.context.provisioning.infrastructure.ProvisioningTaskKind
import com.example.myasr.context.provisioning.infrastructure.ProvisioningTaskRequest
import com.example.myasr.context.provisioning.infrastructure.ProvisioningUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProvisioningBackgroundServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `ProvisioningUiState detects active and queued tasks accurately`() {
        val active = ProvisioningTaskRequest(ProvisioningTaskKind.SINGLE_DICTIONARY, DictionaryType.SUDACHIDICT.id)
        val queued = ProvisioningTaskRequest(ProvisioningTaskKind.ENGLISH_ASR_MODEL)
        val state = ProvisioningUiState(
            isRunning = true,
            activeTask = active,
            queuedTasks = listOf(queued),
            progress = 42,
            statusText = "コンパイル中..."
        )

        assertTrue(state.isTaskActiveOrQueued(ProvisioningTaskKind.SINGLE_DICTIONARY))
        assertTrue(state.isTaskActiveOrQueued(ProvisioningTaskKind.SINGLE_DICTIONARY, DictionaryType.SUDACHIDICT.id))
        assertFalse(state.isTaskActiveOrQueued(ProvisioningTaskKind.SINGLE_DICTIONARY, DictionaryType.JAWIKI.id))
        assertTrue(state.isTaskActiveOrQueued(ProvisioningTaskKind.ENGLISH_ASR_MODEL))
        assertFalse(state.isTaskActiveOrQueued(ProvisioningTaskKind.JAPANESE_ASR_MODEL))
        assertFalse(state.isTaskActiveOrQueued(ProvisioningTaskKind.APP_UPDATE_APK))

        val appUpdateState = state.copy(
            queuedTasks = listOf(queued, ProvisioningTaskRequest(ProvisioningTaskKind.APP_UPDATE_APK))
        )
        assertTrue(appUpdateState.isTaskActiveOrQueued(ProvisioningTaskKind.APP_UPDATE_APK))
    }

    @Test
    fun `ProvisioningTaskRequest displayTitle returns non-empty Japanese labels`() {
        val kinds = listOf(
            ProvisioningTaskRequest(ProvisioningTaskKind.JAPANESE_ASR_MODEL),
            ProvisioningTaskRequest(ProvisioningTaskKind.SINGLE_DICTIONARY, DictionaryType.MOZC_GENERAL.id),
            ProvisioningTaskRequest(ProvisioningTaskKind.ALL_OR_OUTDATED_DICTIONARIES),
            ProvisioningTaskRequest(ProvisioningTaskKind.ENGLISH_SPELLING_MODEL),
            ProvisioningTaskRequest(ProvisioningTaskKind.ENGLISH_ASR_MODEL),
            ProvisioningTaskRequest(ProvisioningTaskKind.APP_UPDATE_APK),
            ProvisioningTaskRequest(ProvisioningTaskKind.APP_UPDATE_APK, "release"),
            ProvisioningTaskRequest(ProvisioningTaskKind.APP_UPDATE_APK, "remote_head")
        )
        kinds.forEach { req ->
            assertTrue("Title should not be blank for $req", req.displayTitle().isNotBlank())
        }
        assertEquals(DictionaryType.MOZC_GENERAL.displayName, kinds[1].displayTitle())
        assertEquals("アプリ最新ビルド", kinds[5].displayTitle())
        assertEquals("リリース版アプリ更新", kinds[6].displayTitle())
        assertEquals("リモートヘッド最新ビルド", kinds[7].displayTitle())
    }

    @Test
    fun `MmapDictionaryCompiler aborts cleanly and removes temp file when cancelled`() {
        val dir = tempFolder.newFolder("dict_cancel_test")
        val targetFile = File(dir, "dict_test.bin")
        val readingMap = mapOf(
            "てすと" to listOf(MmapDictionaryCompiler.RawToken("テスト", 3000, 1851, 1851))
        )

        val result = MmapDictionaryCompiler.compileToFile(
            readingMap = readingMap,
            targetFile = targetFile,
            isCancelled = { true }
        )

        assertTrue(result.isFailure)
        assertFalse(targetFile.exists())
        assertFalse(File(dir, "${targetFile.name}.tmp").exists())
        assertFalse(File(dir, "${targetFile.name}.rt.tmp").exists())
        assertFalse(File(dir, "${targetFile.name}.tt.tmp").exists())
        assertFalse(File(dir, "${targetFile.name}.str.tmp").exists())
    }

    @Test
    fun `MmapDictionaryCompiler StreamingBuilder compiles and loads identically to MmapDictionary`() {
        val dir = tempFolder.newFolder("dict_streaming_test")
        val targetFile = File(dir, "dict_sudachidict.bin")

        val builder = MmapDictionaryCompiler.StreamingBuilder(initialCapacity = 4)
        builder.addToken("すだち", "酢橘", 6000, 1851, 1851)
        builder.addToken("すだち", "Sudachi", 5000, 1851, 1851)
        builder.addToken("あい", "愛", 4000, 1851, 1851)
        builder.addToken("あい", "相", 4500, 1851, 1851)
        builder.addToken("ん", "ん", 3000, 1851, 1851)

        val result = builder.compileToFile(targetFile)
        assertTrue("Compile should succeed", result.isSuccess)
        assertEquals(5, result.getOrNull())
        assertTrue(targetFile.exists())
        assertFalse(File(dir, "${targetFile.name}.tmp").exists())
        assertFalse(File(dir, "${targetFile.name}.rt.tmp").exists())
        assertFalse(File(dir, "${targetFile.name}.tt.tmp").exists())
        assertFalse(File(dir, "${targetFile.name}.str.tmp").exists())

        val mmap = com.example.myasr.context.kanakanji.domain.MmapDictionary.loadFromFile(targetFile)
        assertNotNull("MmapDictionary should load", mmap)
        mmap!!.use { dict ->
            assertEquals(3, dict.numReadings)
            assertEquals(5, dict.numTokens)

            val sudachiTokens = dict.getExactTokens("すだち")
            assertNotNull(sudachiTokens)
            assertEquals(2, sudachiTokens!!.size)
            // Sorted by cost ascending
            assertEquals("Sudachi", sudachiTokens[0].surface)
            assertEquals(5000, sudachiTokens[0].cost)
            assertEquals("酢橘", sudachiTokens[1].surface)
            assertEquals(6000, sudachiTokens[1].cost)

            val aiTokens = dict.getExactTokens("あい")
            assertNotNull(aiTokens)
            assertEquals("愛", aiTokens!![0].surface)
            assertEquals("相", aiTokens[1].surface)
        }
    }
}
