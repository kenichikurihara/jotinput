package com.example.myasr

import android.app.Application
import com.example.myasr.context.provisioning.domain.ApkInstallCompatibilityStatus
import com.example.myasr.context.provisioning.domain.AppUpdateCheckErrorKind
import com.example.myasr.context.provisioning.domain.AppUpdateCheckResult
import com.example.myasr.context.provisioning.domain.BuildFreshnessStatus
import com.example.myasr.context.provisioning.infrastructure.AppUpdateInstaller
import com.example.myasr.context.provisioning.infrastructure.AppUpdateRepository
import com.example.myasr.context.provisioning.infrastructure.GitHubArtifactUpdateClient
import com.example.myasr.context.provisioning.infrastructure.GitHubTokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GitHubArtifactUpdateClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `parseArtifactsJson and selectLatestValidArtifact pick newest non-expired main app-debug`() {
        val sampleJson = """
            {
              "total_count": 5,
              "artifacts": [
                {
                  "id": 999,
                  "name": "app-debug",
                  "size_in_bytes": 38000000,
                  "archive_download_url": "https://api.github.com/repos/kenichikurihara/jotinput/actions/artifacts/999/zip",
                  "expired": true,
                  "created_at": "2026-11-23T05:00:00Z",
                  "expires_at": "2026-11-24T05:00:00Z",
                  "workflow_run": { "id": 5000, "head_branch": "main", "head_sha": "expiredsha" }
                },
                {
                  "id": 998,
                  "name": "app-debug",
                  "size_in_bytes": 38000000,
                  "archive_download_url": "https://api.github.com/repos/kenichikurihara/jotinput/actions/artifacts/998/zip",
                  "expired": false,
                  "created_at": "2026-11-23T04:00:00Z",
                  "expires_at": "2026-11-24T04:00:00Z",
                  "workflow_run": { "id": 4999, "head_branch": "feature/pr", "head_sha": "prbranchsha" }
                },
                {
                  "id": 997,
                  "name": "test-reports",
                  "size_in_bytes": 114236,
                  "archive_download_url": "https://api.github.com/repos/kenichikurihara/jotinput/actions/artifacts/997/zip",
                  "expired": false,
                  "created_at": "2026-11-23T04:30:00Z",
                  "expires_at": "2026-11-24T04:30:00Z",
                  "workflow_run": { "id": 4998, "head_branch": "main", "head_sha": "reportsha" }
                },
                {
                  "id": 14784550253,
                  "name": "app-debug",
                  "size_in_bytes": 37094120,
                  "archive_download_url": "https://api.github.com/repos/kenichikurihara/jotinput/actions/artifacts/14784550253/zip",
                  "expired": false,
                  "created_at": "2026-11-23T02:02:15Z",
                  "expires_at": "2026-11-24T02:02:15Z",
                  "workflow_run": { "id": 45096964189, "head_branch": "main", "head_sha": "f7fedecadec38e1d6f849dcdb9fd499a61f36a5a" }
                },
                {
                  "id": 14784984408,
                  "name": "app-debug",
                  "size_in_bytes": 37159488,
                  "archive_download_url": "https://api.github.com/repos/kenichikurihara/jotinput/actions/artifacts/14784984408/zip",
                  "expired": false,
                  "created_at": "2026-11-23T02:37:57Z",
                  "expires_at": "2026-11-24T02:37:57Z",
                  "workflow_run": { "id": 45100839714, "head_branch": "main", "head_sha": "0101507f7e2d0e37aa1ec7d69abdebc8929152d0" }
                }
              ]
            }
        """.trimIndent()

        val parsed = GitHubArtifactUpdateClient.parseArtifactsJson(sampleJson)
        assertEquals(5, parsed.size)

        val selected = GitHubArtifactUpdateClient.selectLatestValidArtifact(
            artifacts = parsed,
            artifactName = "app-debug",
            targetBranch = "main"
        )
        assertNotNull(selected)
        assertEquals(14784984408L, selected!!.artifactId)
        assertEquals(45100839714L, selected.runId)
        assertEquals("0101507f7e2d0e37aa1ec7d69abdebc8929152d0", selected.headSha)
        assertEquals("0101507", selected.shortSha)
    }

    @Test
    fun `isGitHubApiHost strictly accepts only HTTPS api_github_com`() {
        assertTrue(GitHubArtifactUpdateClient.isGitHubApiHost("https://api.github.com/repos/kenichikurihara/jotinput/actions/artifacts/1/zip"))
        assertFalse(GitHubArtifactUpdateClient.isGitHubApiHost("http://api.github.com/repos/kenichikurihara/jotinput/actions/artifacts/1/zip"))
        assertFalse(GitHubArtifactUpdateClient.isGitHubApiHost("https://pipelinesghubeus23.blob.core.windows.net/sas-url"))
        assertFalse(GitHubArtifactUpdateClient.isGitHubApiHost("https://evil.com/api.github.com"))
        assertFalse(GitHubArtifactUpdateClient.isGitHubApiHost("not-a-url"))
    }

    @Test
    fun `extractApkFromZip extracts valid APK safely and rejects Zip-Slip or corrupt archives`() {
        val dir = tempFolder.newFolder("apk_extract")
        val validZip = File(dir, "valid.zip")
        val destApk = File(dir, "jotinput-update-1.apk")

        // Create a zip containing a path-traversal APK entry with PK\x03\x04 magic bytes
        ZipOutputStream(FileOutputStream(validZip)).use { zos ->
            zos.putNextEntry(ZipEntry("../../evil/app-debug.apk"))
            zos.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            zos.write(ByteArray(2048) { 0x41 })
            zos.closeEntry()
        }

        val okRes = GitHubArtifactUpdateClient.extractApkFromZip(
            zipFile = validZip,
            destApkFile = destApk,
            minApkBytes = 512L
        )
        assertTrue("Valid APK entry should succeed even with traversal name", okRes.isSuccess)
        assertTrue(destApk.exists())
        assertFalse(File(dir, "${destApk.name}.tmp").exists())
        assertFalse(File(dir.parentFile, "evil/app-debug.apk").exists())

        // Zip without .apk entry fails cleanly
        val noApkZip = File(dir, "no_apk.zip")
        ZipOutputStream(FileOutputStream(noApkZip)).use { zos ->
            zos.putNextEntry(ZipEntry("README.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }
        val missingApkDest = File(dir, "missing.apk")
        val noApkRes = GitHubArtifactUpdateClient.extractApkFromZip(
            zipFile = noApkZip,
            destApkFile = missingApkDest,
            minApkBytes = 10L
        )
        assertTrue(noApkRes.isFailure)
        assertFalse(missingApkDest.exists())
        assertFalse(File(dir, "${missingApkDest.name}.tmp").exists())

        // Zip containing invalid magic bytes is rejected
        val fakeApkZip = File(dir, "fake_apk.zip")
        ZipOutputStream(FileOutputStream(fakeApkZip)).use { zos ->
            zos.putNextEntry(ZipEntry("app-debug.apk"))
            zos.write("NOT_AN_APK_ARCHIVE_PAYLOAD_1234567890".toByteArray())
            zos.closeEntry()
        }
        val fakeDest = File(dir, "fake.apk")
        val fakeRes = GitHubArtifactUpdateClient.extractApkFromZip(
            zipFile = fakeApkZip,
            destApkFile = fakeDest,
            minApkBytes = 10L
        )
        assertTrue(fakeRes.isFailure)
        assertFalse(fakeDest.exists())
        assertFalse(File(dir, "${fakeDest.name}.tmp").exists())
    }

    @Test
    fun `extractApkFromZip handles STORED entry with bit-3 data descriptor from GitHub upload-artifact v4`() {
        val dir = tempFolder.newFolder("apk_stored_bit3")
        val zipFile = File(dir, "artifact.zip")
        val destApk = File(dir, "jotinput-update-stored.apk")

        val entryName = "app-debug.apk".toByteArray(Charsets.UTF_8)
        val payload = ByteArray(4100) { idx ->
            when (idx) {
                0 -> 0x50.toByte()
                1 -> 0x4B.toByte()
                2 -> 0x03.toByte()
                3 -> 0x04.toByte()
                else -> (idx % 251).toByte()
            }
        }
        val crc = CRC32().apply { update(payload) }.value.toInt()
        val size = payload.size

        // Construct raw ZIP mimicking `actions/upload-artifact@v4` with `compression-level: 0`:
        // Local File Header has method=0 (STORED), flags=0x08 (Data Descriptor), crc=0, csize=0, usize=0.
        val buf = ByteBuffer.allocate(30 + entryName.size + size + 16 + 46 + entryName.size + 22)
            .order(ByteOrder.LITTLE_ENDIAN)

        // Local File Header (30 bytes + filename)
        buf.putInt(0x04034b50)
        buf.putShort(20)
        buf.putShort(0x0008)
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(0)
        buf.putInt(0)
        buf.putInt(0)
        buf.putInt(0)
        buf.putShort(entryName.size.toShort())
        buf.putShort(0)
        buf.put(entryName)

        // Uncompressed payload
        buf.put(payload)

        // Data Descriptor (16 bytes)
        buf.putInt(0x08074b50)
        buf.putInt(crc)
        buf.putInt(size)
        buf.putInt(size)

        // Central Directory Header (46 bytes + filename)
        val cdOffset = buf.position()
        buf.putInt(0x02014b50)
        buf.putShort(20)
        buf.putShort(20)
        buf.putShort(0x0008)
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(0)
        buf.putInt(crc)
        buf.putInt(size)
        buf.putInt(size)
        buf.putShort(entryName.size.toShort())
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(0)
        buf.putInt(0)
        buf.putInt(0)
        buf.put(entryName)
        val cdSize = buf.position() - cdOffset

        // End of Central Directory (22 bytes)
        buf.putInt(0x06054b50)
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(1)
        buf.putShort(1)
        buf.putInt(cdSize)
        buf.putInt(cdOffset)
        buf.putShort(0)

        zipFile.writeBytes(buf.array().copyOf(buf.position()))

        val res = GitHubArtifactUpdateClient.extractApkFromZip(
            zipFile = zipFile,
            destApkFile = destApk,
            minApkBytes = 512L
        )
        assertTrue("Expected STORED bit-3 descriptor ZIP extraction to succeed, got: ${res.exceptionOrNull()}", res.isSuccess)
        assertTrue(destApk.exists())
        assertFalse(File(dir, "${destApk.name}.tmp").exists())
        assertEquals(payload.size.toLong(), destApk.length())
        assertTrue(payload.contentEquals(destApk.readBytes()))
    }

    @Test
    fun `GitHubTokenStore saves, masks, clears, and fails safe on corrupt storage`() {
        val context = RuntimeEnvironment.getApplication()
        val store = GitHubTokenStore(context)
        store.clearToken()

        assertFalse(store.hasToken())
        assertNull(store.getToken())
        assertNull(store.getMaskedPreview())
        assertFalse(store.saveToken("   "))

        val samplePat = "github_pat_11ABCDEF0123456789_XYZ987"
        assertTrue(store.saveToken("  $samplePat  "))
        assertTrue(store.hasToken())
        assertEquals(samplePat, store.getToken())
        assertEquals("github_pat_••••Z987", store.getMaskedPreview())

        // Corrupt the underlying file and confirm fail-safe read (returns null, no crash)
        val tokenFile = File(context.noBackupFilesDir, "github_actions_pat.dat")
        assertTrue(tokenFile.exists())
        tokenFile.writeText("{corrupt_json_without_closing")
        assertNull(store.getToken())
        assertFalse(store.hasToken())

        store.clearToken()
        assertFalse(tokenFile.exists())
    }

    @Test
    fun `AppUpdateRepository fails safe with TOKEN_REQUIRED when no token is configured`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppUpdateRepository(context)
        repo.tokenStore.clearToken()
        repo.clearCachedRemoteState()

        val result = repo.checkLatestBuild()
        assertTrue(result is AppUpdateCheckResult.Failure)
        val failure = result as AppUpdateCheckResult.Failure
        assertEquals(AppUpdateCheckErrorKind.TOKEN_REQUIRED, failure.errorKind)
        assertNull(repo.getCurrentFreshnessStatus())
        assertNull(repo.getMatchingDownloadedApk())
    }

    @Test
    fun `AppUpdateRepository cleans up obsolete downloaded APK matching current build`() {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppUpdateRepository(context)
        val installed = repo.getInstalledBuildInfo()
        assertTrue(installed.isDebugBuild)

        val updatesDir = repo.getUpdatesDir()
        val staleApk = File(updatesDir, "jotinput-update-777.apk")
        staleApk.outputStream().use { out ->
            out.write(ByteArray(GitHubArtifactUpdateClient.MIN_VALID_APK_BYTES.toInt() + 1024))
        }
        assertTrue(staleApk.exists())

        val prefs = context.getSharedPreferences("app_update_metadata", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("dl_artifact_id", 777L)
            .putString("dl_head_sha", installed.gitSha)
            .putLong("dl_run_id", installed.githubRunId)
            .putLong("dl_created_at_ms", installed.commitEpochMs)
            .putString("dl_file_path", staleApk.absolutePath)
            .putLong("dl_size_bytes", staleApk.length())
            .apply()

        assertNull(repo.getMatchingDownloadedApk())
        assertFalse("Stale APK already matching installed build should be deleted", staleApk.exists())
    }

    @Test
    fun `AppUpdateRepository disables debug artifact checks and downloads in release builds`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppUpdateRepository(context, isDebugBuild = false)
        assertFalse(repo.getInstalledBuildInfo().isDebugBuild)

        val checkResult = repo.checkLatestBuild()
        assertTrue(checkResult is AppUpdateCheckResult.Checked)
        val checked = checkResult as AppUpdateCheckResult.Checked
        assertNull(checked.artifact)
        assertEquals(BuildFreshnessStatus.NO_ARTIFACT, checked.freshness)

        val dlResult = repo.downloadLatestArtifact { _, _ -> }
        assertTrue(dlResult.isFailure)
    }

    @Test
    fun `AppUpdateInstaller formats sha256 fingerprints and flags unreadable or missing APKs`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            AppUpdateInstaller.sha256Hex(ByteArray(0))
        )
        assertEquals(
            "542F:5168:5D68:34C0",
            AppUpdateInstaller.formatShortFingerprint(
                "54:2f:51:68:5d:68:34:c0:e6:0f:19:75:fd:71:06:4e"
            )
        )
        assertEquals("unknown", AppUpdateInstaller.formatShortFingerprint("   "))

        val context = RuntimeEnvironment.getApplication()
        val missingApk = File(tempFolder.root, "does-not-exist.apk")
        val missingRes = AppUpdateInstaller.inspectApkCompatibility(context, missingApk)
        assertEquals(ApkInstallCompatibilityStatus.UNREADABLE_ARCHIVE, missingRes.decision.status)
        assertFalse(missingRes.decision.canProceedToInstaller)

        val corruptApk = File(tempFolder.root, "corrupt.apk").apply {
            writeText("not-a-valid-android-package")
        }
        val corruptRes = AppUpdateInstaller.inspectApkCompatibility(context, corruptApk)
        assertEquals(ApkInstallCompatibilityStatus.UNREADABLE_ARCHIVE, corruptRes.decision.status)
        assertFalse(corruptRes.decision.canProceedToInstaller)
    }

    @Test
    fun `AppUpdateRepository clearStagedDownloadedApk deletes staged APK and metadata`() {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppUpdateRepository(context)
        val installed = repo.getInstalledBuildInfo()
        val updatesDir = repo.getUpdatesDir()
        val stagedApk = File(updatesDir, "jotinput-update-888.apk").apply {
            outputStream().use { out ->
                out.write(ByteArray(GitHubArtifactUpdateClient.MIN_VALID_APK_BYTES.toInt() + 1024))
            }
        }
        assertTrue(stagedApk.exists())

        // Derive newer CI runId/timestamp relative to installed BuildConfig metadata so this test
        // holds both locally (BUILD_GITHUB_RUN_ID=0) and on GitHub Actions (11-digit run IDs).
        val newerRunId = installed.githubRunId.coerceAtLeast(0L) + 1000L
        val newerCreatedAtMs = maxOf(System.currentTimeMillis(), installed.commitEpochMs) + 60_000L
        val distinctSha = if (installed.gitSha.equals("deadbeefdeadbeef", ignoreCase = true)) {
            "cafebabecafebabe"
        } else {
            "deadbeefdeadbeef"
        }

        val prefs = context.getSharedPreferences("app_update_metadata", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("dl_artifact_id", 888L)
            .putString("dl_head_sha", distinctSha)
            .putLong("dl_run_id", newerRunId)
            .putLong("dl_created_at_ms", newerCreatedAtMs)
            .putString("dl_file_path", stagedApk.absolutePath)
            .putLong("dl_size_bytes", stagedApk.length())
            .apply()

        assertNotNull(repo.getMatchingDownloadedApk(latestRemote = null))

        repo.clearStagedDownloadedApk()

        assertFalse("Staged APK file should be deleted", stagedApk.exists())
        assertNull(repo.getMatchingDownloadedApk(latestRemote = null))
    }

    @Test
    fun `CI and Gradle build enforce persistent debug keystore signing to prevent INSTALL_FAILED_UPDATE_INCOMPATIBLE`() {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null &&
            !(File(dir, ".github/workflows/android-ci.yml").isFile && File(dir, "app/build.gradle.kts").isFile)
        ) {
            dir = dir.parentFile
        }
        val repoRoot = dir ?: error("Cannot resolve repository root from ${System.getProperty("user.dir")}")

        val gradleKts = File(repoRoot, "app/build.gradle.kts").readText()
        assertTrue(
            "app/build.gradle.kts must support DEBUG_STORE_FILE persistent debug signing",
            gradleKts.contains("DEBUG_STORE_FILE") && gradleKts.contains(".config/jotinput/debug.keystore")
        )

        val ciYml = File(repoRoot, ".github/workflows/android-ci.yml").readText()
        assertTrue(
            "android-ci.yml must decode DEBUG_KEYSTORE_BASE64 and set DEBUG_STORE_FILE",
            ciYml.contains("DEBUG_KEYSTORE_BASE64") && ciYml.contains("DEBUG_STORE_FILE=")
        )
        assertTrue(
            "android-ci.yml must verify app-debug.apk signature against persistent debug keystore via apksigner",
            ciYml.contains("apksigner") && ciYml.contains("Signer #1 certificate SHA-256 digest:")
        )
    }
}
