import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.myasr"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    buildFeatures {
        buildConfig = true
    }

    val gitShaProvider = providers.environmentVariable("GITHUB_SHA")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .orElse(
            providers.exec {
                commandLine("git", "rev-parse", "HEAD")
                isIgnoreExitValue = true
            }.standardOutput.asText.map { it.trim() }
        )
        .map { raw ->
            raw.replace(Regex("[^0-9a-fA-F]"), "").ifEmpty { "unknown" }
        }

    val gitCommitEpochMsProvider = providers.exec {
        commandLine("git", "show", "-s", "--format=%ct", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { out ->
        (out.trim().toLongOrNull() ?: 0L) * 1000L
    }

    val githubRunIdProvider = providers.environmentVariable("GITHUB_RUN_ID")
        .map { it.trim().toLongOrNull() ?: 0L }
        .orElse(0L)

    defaultConfig {
        applicationId = "io.github.kenichikurihara.jotinput"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "ja")

        buildConfigField("String", "BUILD_GIT_SHA", "\"${gitShaProvider.get()}\"")
        buildConfigField("long", "BUILD_GIT_COMMIT_EPOCH_MS", "${gitCommitEpochMsProvider.get()}L")
        buildConfigField("long", "BUILD_GITHUB_RUN_ID", "${githubRunIdProvider.get()}L")
        buildConfigField("String", "GITHUB_REPO_OWNER", "\"kenichikurihara\"")
        buildConfigField("String", "GITHUB_REPO_NAME", "\"jotinput\"")
        buildConfigField("String", "GITHUB_ARTIFACT_NAME", "\"app-debug\"")
        buildConfigField("String", "GITHUB_TARGET_BRANCH", "\"main\"")

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                cFlags += listOf("-O3")
                cppFlags += listOf("-std=c++17", "-O3")
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
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

    if (hasReleaseSigning) {
        signingConfigs {
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
    testImplementation("org.json:json:20240303")
    testImplementation("com.lemonappdev:konsist:0.17.3")
    testImplementation("org.alloytools:org.alloytools.alloy.core:6.2.0")
    testImplementation("org.robolectric:robolectric:4.13")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
