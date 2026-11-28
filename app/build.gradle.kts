import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

abstract class GenerateBuildMetadataTask : DefaultTask() {
    @get:Input
    abstract val repoRootPath: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun generate() {
        val repoDir = File(repoRootPath.get())
        val envSha = System.getenv("GITHUB_SHA")?.trim()?.takeIf { it.isNotEmpty() }
        val rawSha = envSha ?: runGit(repoDir, "rev-parse", "HEAD")
        val gitSha = rawSha.replace(Regex("[^0-9a-fA-F]"), "").ifEmpty { "unknown" }

        val rawEpoch = runGit(repoDir, "show", "-s", "--format=%ct", "HEAD")
        val commitEpochMs = (rawEpoch.toLongOrNull() ?: 0L) * 1000L

        val githubRunId = System.getenv("GITHUB_RUN_ID")?.trim()?.toLongOrNull() ?: 0L

        val content = buildString {
            append("gitSha=").append(gitSha).append('\n')
            append("commitEpochMs=").append(commitEpochMs).append('\n')
            append("githubRunId=").append(githubRunId).append('\n')
        }

        val outFile = outputDir.get().file("build_metadata.properties").asFile
        outFile.parentFile?.mkdirs()
        if (!outFile.isFile || outFile.readText(Charsets.UTF_8) != content) {
            outFile.writeText(content, Charsets.UTF_8)
        }
    }

    private fun runGit(workDir: File, vararg args: String): String {
        val stdout = ByteArrayOutputStream()
        execOps.exec {
            workingDir = workDir
            commandLine("git", *args)
            standardOutput = stdout
            isIgnoreExitValue = true
        }
        return stdout.toString(Charsets.UTF_8.name()).trim()
    }
}

android {
    namespace = "com.example.myasr"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "io.github.kenichikurihara.jotinput"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "ja")

        buildConfigField("String", "GITHUB_REPO_OWNER", "\"kenichikurihara\"")
        buildConfigField("String", "GITHUB_REPO_NAME", "\"jotinput\"")
        buildConfigField("String", "GITHUB_HEAD_RELEASE_TAG", "\"main-head\"")
        buildConfigField("String", "GITHUB_HEAD_METADATA_ASSET", "\"head-metadata.json\"")
        buildConfigField("String", "GITHUB_TARGET_BRANCH", "\"main\"")

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                cFlags += listOf("-O3")
                cppFlags += listOf("-std=c++17", "-O3")
                arguments += listOf(
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_BUILD_TYPE=RelWithDebInfo"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    val releaseSigningProps = run {
        val envStoreFile = providers.environmentVariable("RELEASE_STORE_FILE").orNull?.trim()
        val envStorePass = providers.environmentVariable("RELEASE_STORE_PASSWORD").orNull
        val envKeyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull?.trim()
        val envKeyPass = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
        if (!envStoreFile.isNullOrEmpty() && !envStorePass.isNullOrEmpty() &&
            !envKeyAlias.isNullOrEmpty() && !envKeyPass.isNullOrEmpty()
        ) {
            Properties().apply {
                setProperty("storeFile", envStoreFile)
                setProperty("storePassword", envStorePass)
                setProperty("keyAlias", envKeyAlias)
                setProperty("keyPassword", envKeyPass)
            }
        } else {
            val candidates = listOf(
                rootProject.file("keystore.properties"),
                File(System.getProperty("user.home"), ".config/jotinput/keystore.properties")
            )
            val propsFile = candidates.firstOrNull { it.isFile }
            if (propsFile != null) {
                Properties().apply { propsFile.inputStream().use(::load) }
            } else {
                null
            }
        }
    }

    val releaseStoreFile = releaseSigningProps?.getProperty("storeFile")?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { path -> File(path).let { if (it.isAbsolute) it else rootProject.file(path) } }
        ?.takeIf { it.isFile }
    val releaseStorePassword = releaseSigningProps?.getProperty("storePassword")?.takeIf { it.isNotEmpty() }
    val releaseKeyAlias = releaseSigningProps?.getProperty("keyAlias")?.trim()?.takeIf { it.isNotEmpty() }
    val releaseKeyPassword = releaseSigningProps?.getProperty("keyPassword")?.takeIf { it.isNotEmpty() }
    val hasReleaseSigning = releaseStoreFile != null &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null

    val debugStoreFile = run {
        val envStoreFile = providers.environmentVariable("DEBUG_STORE_FILE").orNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { path -> File(path).let { if (it.isAbsolute) it else rootProject.file(path) } }
            ?.takeIf { it.isFile }
        if (envStoreFile != null) {
            envStoreFile
        } else {
            val userHome = System.getProperty("user.home")
            listOf(
                File(userHome, ".config/jotinput/debug.keystore"),
                File(userHome, ".config/.android/debug.keystore")
            ).firstOrNull { it.isFile }
        }
    }
    val debugStorePassword = providers.environmentVariable("DEBUG_STORE_PASSWORD").orNull
        ?.takeIf { it.isNotEmpty() } ?: "android"
    val debugKeyAlias = providers.environmentVariable("DEBUG_KEY_ALIAS").orNull?.trim()
        ?.takeIf { it.isNotEmpty() } ?: "androiddebugkey"
    val debugKeyPassword = providers.environmentVariable("DEBUG_KEY_PASSWORD").orNull
        ?.takeIf { it.isNotEmpty() } ?: "android"

    signingConfigs {
        if (debugStoreFile != null) {
            getByName("debug") {
                storeFile = debugStoreFile
                storePassword = debugStorePassword
                keyAlias = debugKeyAlias
                keyPassword = debugKeyPassword
            }
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += setOf(
                "**/libsherpa-onnx-c-api.so",
                "**/libsherpa-onnx-cxx-api.so"
            )
        }
        resources {
            excludes += setOf(
                "DebugProbesKt.bin",
                "META-INF/*.version",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }

    // Assets and native libs are compressed & unused transitive libraries stripped for compact APK (~36.9 MB debug / ~29.5 MB release)
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    val rootDirPath = rootProject.layout.projectDirectory.asFile.absolutePath
    onVariants { variant ->
        val capName = variant.name.replaceFirstChar { it.uppercase() }
        val taskProvider = tasks.register<GenerateBuildMetadataTask>(
            "generate${capName}BuildMetadata"
        ) {
            repoRootPath.set(rootDirPath)
            outputs.upToDateWhen { false }
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            taskProvider,
            GenerateBuildMetadataTask::outputDir
        )
    }
}

tasks.withType<Test>().configureEach {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 2)
    forkEvery = 0
    jvmArgs("-Xmx1024m")
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0") {
        exclude(group = "androidx.viewpager2", module = "viewpager2")
        exclude(group = "androidx.legacy", module = "legacy-support-core-utils")
        exclude(group = "androidx.documentfile", module = "documentfile")
        exclude(group = "androidx.localbroadcastmanager", module = "localbroadcastmanager")
        exclude(group = "androidx.print", module = "print")
    }
    implementation("androidx.recyclerview:recyclerview:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("org.json:json:20240303")
    testImplementation("com.lemonappdev:konsist:0.17.3")
    testImplementation("org.alloytools:org.alloytools.alloy.core:6.2.0")
    testImplementation("org.robolectric:robolectric:4.13")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
