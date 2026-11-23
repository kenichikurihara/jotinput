plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.myasr"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.example.myasr"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "ja")

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

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
