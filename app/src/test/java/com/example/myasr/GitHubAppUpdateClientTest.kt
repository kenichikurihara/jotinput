package com.example.myasr

import android.app.Application
import android.content.Context
import com.example.myasr.context.provisioning.domain.ApkInstallCompatibilityStatus
import com.example.myasr.context.provisioning.domain.AppUpdateChannel
import com.example.myasr.context.provisioning.domain.AppUpdateCheckErrorKind
import com.example.myasr.context.provisioning.infrastructure.AppUpdateInstaller
import com.example.myasr.context.provisioning.infrastructure.AppUpdateRepository
import com.example.myasr.context.provisioning.infrastructure.GitHubAppUpdateClient
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
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GitHubAppUpdateClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    @Test
    fun `parseStableReleaseJson extracts signed release APK and rejects drafts or prereleases`() {
        val sampleJson = """
            {
              "tag_name": "v0.1.0",
              "target_commitish": "main",
              "draft": false,
              "prerelease": false,
              "published_at": "2026-11-26T12:00:58Z",
              "assets": [
                {
                  "id": 795693356,
                  "name": "SHA256SUMS.txt",
                  "size": 103,
                  "digest": "sha256:3ad228b487157d48f01711e0631608abd6362573eef4e8eefd8ad62d28a965f2",
                  "browser_download_url": "https://github.com/kenichikurihara/jotinput/releases/download/v0.1.0/SHA256SUMS.txt"
                },
                {
                  "id": 795693354,
                  "name": "extra-preview.apk",
                  "size": 12000000,
                  "digest": "",
                  "browser_download_url": "https://github.com/kenichikurihara/jotinput/releases/download/v0.1.0/extra-preview.apk"
                },
                {
                  "id": 795693355,
                  "name": "jotinput-0.1.0-universal-release.apk",
                  "size": 29895313,
                  "digest": "sha256:ed0bcff591008863fa9ab3e91bcbdfcd890f04ce58da76aee1929f3f8f41bbba",
                  "browser_download_url": "https://github.com/kenichikurihara/jotinput/releases/download/v0.1.0/jotinput-0.1.0-universal-release.apk"
                }
              ]
            }
        """.trimIndent()

        val parsed = GitHubAppUpdateClient.parseStableReleaseJson(sampleJson)
        assertNotNull(parsed)
        assertEquals(AppUpdateChannel.RELEASE, parsed!!.channel)
        assertEquals("v0.1.0", parsed.tagName)
        assertEquals("0.1.0", parsed.versionName)
        assertEquals("jotinput-0.1.0-universal-release.apk", parsed.name)
        assertEquals(29895313L, parsed.sizeInBytes)
        assertEquals("ed0bcff591008863fa9ab3e91bcbdfcd890f04ce58da76aee1929f3f8f41bbba", parsed.sha256)
        assertFalse(parsed.isDebugVariant)

        val prereleaseJson = sampleJson.replace("\"prerelease\": false", "\"prerelease\": true")
        assertNull(GitHubAppUpdateClient.parseStableReleaseJson(prereleaseJson))

        val draftJson = sampleJson.replace("\"draft\": false", "\"draft\": true")
        assertNull(GitHubAppUpdateClient.parseStableReleaseJson(draftJson))
    }

    @Test
    fun `parseHeadMetadataJson selects variant matching running build without cross-variant fallback`() {
        val metadataJson = """
            {
              "schemaVersion": 1,
              "tag": "main-head",
              "branch": "main",
              "gitSha": "291ca9c1234567890abcdef1234567890abcdef1",
              "commitEpochMs": 1795820000000,
              "publishedAtMs": 1795820600000,
              "runId": 46091560000,
              "versionName": "0.1.0",
              "versionCode": 1,
              "debugApk": {
                "name": "jotinput-main-head-debug.apk",
                "downloadUrl": "https://github.com/kenichikurihara/jotinput/releases/download/main-head/jotinput-main-head-debug.apk",
                "sizeBytes": 37385756,
                "sha256": "1111111111111111111111111111111111111111111111111111111111111111"
              },
              "releaseApk": {
                "name": "jotinput-main-head-release.apk",
                "downloadUrl": "https://github.com/kenichikurihara/jotinput/releases/download/main-head/jotinput-main-head-release.apk",
                "sizeBytes": 29895313,
                "sha256": "2222222222222222222222222222222222222222222222222222222222222222"
              }
            }
        """.trimIndent()

        val debugParsed = GitHubAppUpdateClient.parseHeadMetadataJson(metadataJson, preferDebugVariant = true)
        assertNotNull(debugParsed)
        assertEquals(AppUpdateChannel.REMOTE_HEAD, debugParsed!!.channel)
        assertTrue(debugParsed.isDebugVariant)
        assertEquals("jotinput-main-head-debug.apk", debugParsed.name)
        assertEquals("291ca9c", debugParsed.shortSha)
        assertEquals(1795820000000L, debugParsed.commitEpochMs)
        assertEquals(46091560000L, debugParsed.runId)

        val releaseParsed = GitHubAppUpdateClient.parseHeadMetadataJson(metadataJson, preferDebugVariant = false)
        assertNotNull(releaseParsed)
        assertFalse(releaseParsed!!.isDebugVariant)
        assertEquals("jotinput-main-head-release.apk", releaseParsed.name)
        assertEquals(
            "2222222222222222222222222222222222222222222222222222222222222222",
            releaseParsed.sha256
        )

        val noDebugJson = metadataJson.replace("\"debugApk\"", "\"ignoredDebugApk\"")
        assertNull(GitHubAppUpdateClient.parseHeadMetadataJson(noDebugJson, preferDebugVariant = true))
    }

    @Test
    fun `isTrustedGitHubDownloadHost strictly accepts HTTPS GitHub and githubusercontent hosts`() {
        assertTrue(GitHubAppUpdateClient.isTrustedGitHubDownloadHost("https://github.com/kenichikurihara/jotinput/releases/download/v0.1.0/app.apk"))
        assertTrue(GitHubAppUpdateClient.isTrustedGitHubDownloadHost("https://api.github.com/repos/kenichikurihara/jotinput/releases/latest"))
        assertTrue(GitHubAppUpdateClient.isTrustedGitHubDownloadHost("https://objects.githubusercontent.com/github-production-release-asset/foo.apk"))
        assertTrue(GitHubAppUpdateClient.isTrustedGitHubDownloadHost("https://release-assets.githubusercontent.com/foo.apk"))

        assertFalse(GitHubAppUpdateClient.isTrustedGitHubDownloadHost("http://github.com/kenichikurihara/jotinput/releases/download/v0.1.0/app.apk"))
        assertFalse(GitHubAppUpdateClient.isTrustedGitHubDownloadHost("https://evilgithubusercontent.com/foo.apk"))
        assertFalse(GitHubAppUpdateClient.isTrustedGitHubDownloadHost("https://evil.com/github.com/foo.apk"))
        assertFalse(GitHubAppUpdateClient.isTrustedGitHubDownloadHost("not-a-url"))
    }

    @Test
    fun `streamAndVerifyApk validates PK header, bounds, and SHA-256 digest atomically`() {
        val dir = tempFolder.newFolder("apk_stream")
        val payload = ByteArray(4096) { idx ->
            when (idx) {
                0 -> 0x50.toByte()
                1 -> 0x4B.toByte()
                2 -> 0x03.toByte()
                3 -> 0x04.toByte()
                else -> ((idx * 17) % 251).toByte()
            }
        }
        val validSha = sha256Hex(payload)
        val destOk = File(dir, "valid.apk")

        val okRes = GitHubAppUpdateClient.streamAndVerifyApk(
            inputStream = ByteArrayInputStream(payload),
            destApkFile = destOk,
            expectedSizeBytes = payload.size.toLong(),
            expectedSha256 = validSha,
            minApkBytes = 512L
        )
        assertTrue("Expected valid APK stream verification to pass: ${okRes.exceptionOrNull()}", okRes.isSuccess)
        assertTrue(destOk.exists())
        assertFalse(File(dir, "${destOk.name}.tmp").exists())
        assertEquals(validSha, okRes.getOrThrow().sha256)
        assertEquals(payload.size.toLong(), okRes.getOrThrow().sizeInBytes)

        // SHA-256 mismatch fails with INTEGRITY_CHECK_FAILED and cleans temp file
        val destMismatch = File(dir, "mismatch.apk")
        val mismatchRes = GitHubAppUpdateClient.streamAndVerifyApk(
            inputStream = ByteArrayInputStream(payload),
            destApkFile = destMismatch,
            expectedSizeBytes = payload.size.toLong(),
            expectedSha256 = "0".repeat(64),
            minApkBytes = 512L
        )
        assertTrue(mismatchRes.isFailure)
        val mismatchEx = mismatchRes.exceptionOrNull() as? GitHubAppUpdateClient.DownloadHttpException
        assertNotNull(mismatchEx)
        assertEquals(AppUpdateCheckErrorKind.INTEGRITY_CHECK_FAILED, mismatchEx!!.errorKind)
        assertFalse(destMismatch.exists())
        assertFalse(File(dir, "${destMismatch.name}.tmp").exists())

        // Corrupt non-PK header fails with INTEGRITY_CHECK_FAILED
        val corruptBytes = "NOT_AN_APK_ARCHIVE".repeat(60).toByteArray()
        val destCorrupt = File(dir, "corrupt.apk")
        val corruptRes = GitHubAppUpdateClient.streamAndVerifyApk(
            inputStream = ByteArrayInputStream(corruptBytes),
            destApkFile = destCorrupt,
            expectedSizeBytes = corruptBytes.size.toLong(),
            expectedSha256 = "",
            minApkBytes = 512L
        )
        assertTrue(corruptRes.isFailure)
        val corruptEx = corruptRes.exceptionOrNull() as? GitHubAppUpdateClient.DownloadHttpException
        assertNotNull(corruptEx)
        assertEquals(AppUpdateCheckErrorKind.INTEGRITY_CHECK_FAILED, corruptEx!!.errorKind)
        assertFalse(destCorrupt.exists())
        assertFalse(File(dir, "${destCorrupt.name}.tmp").exists())
    }

    @Test
    fun `AppUpdateRepository manages per-variant channel defaults, legacy PAT cleanup, and cross-channel isolation`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("app_update_metadata", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        // Plant legacy PAT file to confirm automatic removal on init
        val legacyPatFile = File(context.noBackupFilesDir, "github_actions_pat.dat").apply {
            parentFile?.mkdirs()
            writeText("legacy_encrypted_payload")
        }
        assertTrue(legacyPatFile.exists())

        val debugRepo = AppUpdateRepository(context, isDebugBuild = true)
        assertFalse("Legacy PAT file must be deleted automatically", legacyPatFile.exists())
        assertEquals(AppUpdateChannel.REMOTE_HEAD, debugRepo.getDefaultChannel())
        assertEquals(AppUpdateChannel.REMOTE_HEAD, debugRepo.getSelectedChannel())

        val releaseRepo = AppUpdateRepository(context, isDebugBuild = false)
        assertEquals(AppUpdateChannel.RELEASE, releaseRepo.getDefaultChannel())
        assertEquals(AppUpdateChannel.RELEASE, releaseRepo.getSelectedChannel())

        debugRepo.setSelectedChannel(AppUpdateChannel.RELEASE)
        assertEquals(AppUpdateChannel.RELEASE, debugRepo.getSelectedChannel())

        // Stage a newer REMOTE_HEAD APK and verify strict isolation from RELEASE channel
        val installed = debugRepo.getInstalledBuildInfo()
        val updatesDir = debugRepo.getUpdatesDir()
        val stagedApk = File(updatesDir, "jotinput-remote_head-900.apk").apply {
            outputStream().use { out ->
                out.write(ByteArray(GitHubAppUpdateClient.MIN_VALID_APK_BYTES.toInt() + 1024))
            }
        }
        val newerRunId = installed.githubRunId.coerceAtLeast(0L) + 1000L
        val newerCreatedAtMs = maxOf(System.currentTimeMillis(), installed.commitEpochMs) + 60_000L
        val distinctSha = if (installed.gitSha.equals("deadbeefdeadbeef", ignoreCase = true)) {
            "cafebabecafebabe"
        } else {
            "deadbeefdeadbeef"
        }

        prefs.edit()
            .putString("dl_channel", AppUpdateChannel.REMOTE_HEAD.id)
            .putLong("dl_artifact_id", 900L)
            .putString("dl_tag_name", "main-head")
            .putString("dl_version_name", installed.versionName)
            .putInt("dl_version_code", installed.versionCode)
            .putString("dl_head_sha", distinctSha)
            .putLong("dl_run_id", newerRunId)
            .putLong("dl_commit_epoch_ms", newerCreatedAtMs)
            .putLong("dl_created_at_ms", newerCreatedAtMs)
            .putString("dl_file_path", stagedApk.absolutePath)
            .putLong("dl_size_bytes", stagedApk.length())
            .apply()

        // Must NOT surface REMOTE_HEAD APK when RELEASE channel is selected, yet must NOT delete it
        assertNull(debugRepo.getMatchingDownloadedApk(channel = AppUpdateChannel.RELEASE, latestRemote = null))
        assertTrue(stagedApk.exists())

        // Switching to REMOTE_HEAD surfaces the staged APK cleanly
        val matched = debugRepo.getMatchingDownloadedApk(channel = AppUpdateChannel.REMOTE_HEAD, latestRemote = null)
        assertNotNull(matched)
        assertEquals(AppUpdateChannel.REMOTE_HEAD, matched!!.channel)

        debugRepo.clearStagedDownloadedApk()
        assertFalse(stagedApk.exists())
        assertNull(debugRepo.getMatchingDownloadedApk(channel = AppUpdateChannel.REMOTE_HEAD, latestRemote = null))
    }

    @Test
    fun `AppUpdateRepository cleans up obsolete downloaded APK matching installed build`() {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppUpdateRepository(context, isDebugBuild = true)
        val installed = repo.getInstalledBuildInfo()
        val updatesDir = repo.getUpdatesDir()

        val staleApk = File(updatesDir, "jotinput-remote_head-777.apk").apply {
            outputStream().use { out ->
                out.write(ByteArray(GitHubAppUpdateClient.MIN_VALID_APK_BYTES.toInt() + 1024))
            }
        }
        assertTrue(staleApk.exists())

        val prefs = context.getSharedPreferences("app_update_metadata", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("dl_channel", AppUpdateChannel.REMOTE_HEAD.id)
            .putLong("dl_artifact_id", 777L)
            .putString("dl_tag_name", "main-head")
            .putString("dl_version_name", installed.versionName)
            .putInt("dl_version_code", installed.versionCode)
            .putString("dl_head_sha", installed.gitSha)
            .putLong("dl_run_id", installed.githubRunId)
            .putLong("dl_commit_epoch_ms", installed.commitEpochMs)
            .putLong("dl_created_at_ms", installed.commitEpochMs)
            .putString("dl_file_path", staleApk.absolutePath)
            .putLong("dl_size_bytes", staleApk.length())
            .apply()

        assertNull(repo.getMatchingDownloadedApk(channel = AppUpdateChannel.REMOTE_HEAD, latestRemote = null))
        assertTrue("Stale APK matching installed build should be deleted", !staleApk.exists())
    }

    @Test
    fun `AppUpdateRepository loads non-empty gitSha and commitEpochMs from generated build metadata asset`() {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppUpdateRepository(context, isDebugBuild = true)
        val info = repo.getInstalledBuildInfo()
        assertTrue(
            "Expected hex gitSha from generated assets/build_metadata.properties, got '${info.gitSha}'",
            Regex("^[0-9a-fA-F]{7,40}$").matches(info.gitSha)
        )
        assertTrue(
            "Expected positive commitEpochMs from generated assets/build_metadata.properties, got ${info.commitEpochMs}",
            info.commitEpochMs > 0L
        )
        assertTrue(info.githubRunId >= 0L)
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
    fun `CI workflow, Gradle build, and agent instructions enforce tokenless updates and mandatory PR pre-merge CI`() {
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
        assertTrue(
            "app/build.gradle.kts must expose GITHUB_HEAD_RELEASE_TAG and GITHUB_HEAD_METADATA_ASSET",
            gradleKts.contains("GITHUB_HEAD_RELEASE_TAG") && gradleKts.contains("GITHUB_HEAD_METADATA_ASSET")
        )
        assertTrue(
            "app/build.gradle.kts must generate build_metadata.properties via GenerateBuildMetadataTask and unify CMake RelWithDebInfo",
            gradleKts.contains("GenerateBuildMetadataTask") &&
                gradleKts.contains("build_metadata.properties") &&
                gradleKts.contains("-DCMAKE_BUILD_TYPE=RelWithDebInfo")
        )
        assertFalse(
            "app/build.gradle.kts must not bake volatile BUILD_GIT_SHA or BUILD_GITHUB_RUN_ID into BuildConfig",
            gradleKts.contains("BUILD_GIT_SHA") || gradleKts.contains("BUILD_GITHUB_RUN_ID")
        )

        val mainManifest = File(repoRoot, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "Main manifest must declare REQUEST_INSTALL_PACKAGES for both Release and Debug self-updates",
            mainManifest.contains("android.permission.REQUEST_INSTALL_PACKAGES")
        )
        assertFalse(
            "Obsolete debug-only AndroidManifest.xml must be deleted",
            File(repoRoot, "app/src/debug/AndroidManifest.xml").exists()
        )

        val setupAction = File(repoRoot, ".github/actions/setup-android-build/action.yml")
        assertTrue("Composite setup action must exist", setupAction.isFile)
        val setupActionYml = setupAction.readText()
        assertTrue(
            "Composite action must cache static ccache binary and ccache-v3",
            setupActionYml.contains("ccache-bin-v4.14.3") && setupActionYml.contains("ccache-v3-")
        )

        val ciYml = File(repoRoot, ".github/workflows/android-ci.yml").readText()
        val pullRequestBlock = ciYml.substringAfter("pull_request:").substringBefore("workflow_dispatch:")
        assertFalse(
            "pull_request trigger must NEVER use paths-ignore so every PR runs CI before merge",
            pullRequestBlock.contains("paths-ignore")
        )
        assertTrue(
            "android-ci.yml must run parallel test-and-debug and verify-release jobs gated by build-and-test with if: always()",
            ciYml.contains("test-and-debug:") &&
                ciYml.contains("verify-release:") &&
                ciYml.contains("needs: [detect-changes, test-and-debug, verify-release]") &&
                ciYml.contains("if: always()")
        )
        assertTrue(
            "android-ci.yml must decode DEBUG_KEYSTORE_BASE64 and set DEBUG_STORE_FILE",
            ciYml.contains("DEBUG_KEYSTORE_BASE64") && ciYml.contains("DEBUG_STORE_FILE=")
        )
        assertTrue(
            "android-ci.yml must verify app-debug.apk signature against persistent debug keystore via apksigner",
            ciYml.contains("apksigner") &&
                ciYml.contains("certificate SHA-256 digest:") &&
                !ciYml.contains("Signer #1 certificate SHA-256 digest:")
        )
        assertTrue(
            "android-ci.yml must define publish-head job publishing main-head prerelease without overriding latest",
            ciYml.contains("publish-head:") &&
                ciYml.contains("needs: build-and-test") &&
                ciYml.contains("environment: release") &&
                ciYml.contains("main-head") &&
                ciYml.contains("--prerelease") &&
                ciYml.contains("--latest=false") &&
                ciYml.contains("head-metadata.json") &&
                ciYml.contains("jotinput-main-head-release.apk") &&
                ciYml.contains("jotinput-main-head-debug.apk")
        )
        val publishHeadBlock = ciYml.substringAfter("publish-head:")
        assertTrue(
            "publish-head must zipalign + sign the compiled release APK directly without invoking ./gradlew",
            publishHeadBlock.contains("apksigner") &&
                publishHeadBlock.contains("zipalign") &&
                !publishHeadBlock.contains("./gradlew")
        )

        val preCommit = File(repoRoot, ".githooks/pre-commit").readText()
        assertTrue(
            ".githooks/pre-commit must use ../../app/src/ relative symlinks",
            preCommit.contains("../../app/src/main/assets/mozc/connection_data.bin") &&
                preCommit.contains("../../app/src/test/resources/mozc/system_dictionary.bin")
        )
        val prePush = File(repoRoot, ".githooks/pre-push").readText()
        assertTrue(
            ".githooks/pre-push must block direct pushes to refs/heads/main",
            prePush.contains("refs/heads/main")
        )

        val agentsMd = File(repoRoot, "AGENTS.md").readText()
        val geminiMd = File(repoRoot, "GEMINI.md").readText()
        assertTrue(
            "AGENTS.md must require PR creation and pre-merge CI/CD verification",
            agentsMd.contains("gh pr create") && agentsMd.contains("pre-merge", ignoreCase = true)
        )
        assertTrue(
            "GEMINI.md must require PR creation and pre-merge CI/CD verification",
            geminiMd.contains("gh pr create") && geminiMd.contains("pre-merge", ignoreCase = true)
        )
    }
}
