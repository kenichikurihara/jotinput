package com.example.myasr

import com.example.myasr.context.kanakanji.infrastructure.MmapDictionaryCompiler
import com.example.myasr.context.provisioning.infrastructure.DictionaryType
import com.example.myasr.context.provisioning.infrastructure.ProvisioningTaskKind
import com.example.myasr.context.provisioning.infrastructure.ProvisioningTaskRequest
import com.example.myasr.context.provisioning.infrastructure.ProvisioningUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    }

    @Test
    fun `ProvisioningTaskRequest displayTitle returns non-empty Japanese labels`() {
        val kinds = listOf(
            ProvisioningTaskRequest(ProvisioningTaskKind.JAPANESE_ASR_MODEL),
            ProvisioningTaskRequest(ProvisioningTaskKind.SINGLE_DICTIONARY, DictionaryType.MOZC_GENERAL.id),
            ProvisioningTaskRequest(ProvisioningTaskKind.ALL_OR_OUTDATED_DICTIONARIES),
            ProvisioningTaskRequest(ProvisioningTaskKind.ENGLISH_SPELLING_MODEL),
            ProvisioningTaskRequest(ProvisioningTaskKind.ENGLISH_ASR_MODEL)
        )
        kinds.forEach { req ->
            assertTrue("Title should not be blank for $req", req.displayTitle().isNotBlank())
        }
        assertEquals(DictionaryType.MOZC_GENERAL.displayName, kinds[1].displayTitle())
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
    }
}
