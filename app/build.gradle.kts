import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.inboxiq.app"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.inboxiq.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        // Native whisper.cpp build for on-device voice-memo transcription (zero network).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DWHISPER_BUILD_TESTS=OFF",
                    "-DWHISPER_BUILD_EXAMPLES=OFF",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
            }
        }
    }

    // MID tier's Gemma 3 270M model ships as an on-demand Play asset pack, not an
    // APK asset — it's a ~125MB opt-in download, and this way it never needs the
    // INTERNET permission in this module (Play's own infra handles the transfer).
    assetPacks += setOf(":gemma_model_pack")

    signingConfigs {
        create("release") {
            storeFile = file("/Users/sushanthtiruvaipati/Documents/GitHub/AndroidAppKey")
            storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = "androidappkey"
            keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
        }
    }

    buildFeatures {
        compose = true
    }
    // Compose compiler version now comes from the org.jetbrains.kotlin.plugin.compose
    // plugin (matches the Kotlin version) — composeOptions/kotlinCompilerExtensionVersion
    // doesn't exist under Kotlin 2.x.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// Replaces the deprecated android { kotlinOptions { jvmTarget = ... } } block — hard
// compile error under Kotlin 2.x's compiler-options DSL migration.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // On-device classification (default tier): ONNX Runtime Mobile
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    // MMS PDU encoding for send — Apache-2.0 maintained fork of klinker's android-smsmms
    implementation("com.github.FossifyOrg:mmslib:1.0.0")

    // On-device LLM runtime for the MID/HIGH classifier tiers (Gemma 3 270M / Qwen 1.5B)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.15.0")

    // Downloads the gemma_model_pack asset pack on-demand when the user opts into MID tier
    implementation("com.google.android.play:asset-delivery-ktx:2.3.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
