plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.inboxiq.app"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.inboxiq.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "0.5.4"

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
            // Credentials live outside the repo, in ~/.gradle/gradle.properties:
            // ANDROID_UPLOAD_STORE_FILE, ANDROID_UPLOAD_STORE_PASSWORD, ANDROID_UPLOAD_KEY_ALIAS, ANDROID_UPLOAD_KEY_PASSWORD
            val uploadStore = providers.gradleProperty("ANDROID_UPLOAD_STORE_FILE").orNull
            if (uploadStore != null) {
                storeFile = file(uploadStore)
                storePassword = providers.gradleProperty("ANDROID_UPLOAD_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("ANDROID_UPLOAD_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("ANDROID_UPLOAD_KEY_PASSWORD").get()
            } else if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
                throw GradleException("Release signing needs ANDROID_UPLOAD_* in ~/.gradle/gradle.properties (see build.gradle.kts).")
            }
        }
    }

    // Two shippable variants from one codebase (docs/ARCHITECTURE_RELAY_OPTIONAL.md):
    //  core  - no relay code, no INTERNET permission; what the Play listing describes.
    //  relay - core plus the optional, consent-gated "Connect to Claude" relay (opt-in distribution).
    // Same applicationId and signing, so a user's database carries across variants unchanged.
    flavorDimensions += "distribution"
    productFlavors {
        create("core") { dimension = "distribution" }
        create("relay") { dimension = "distribution" }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Compose compiler version now comes from the org.jetbrains.kotlin.plugin.compose
    // plugin (matches the Kotlin version) — composeOptions/kotlinCompilerExtensionVersion
    // doesn't exist under Kotlin 2.x.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        // AUTO_RESPONDER_ENABLED: compile the optional "Auto-reply when away" feature in and show it.
        // Debug builds have it on for testing; release builds ship with it OFF unless the owner
        // opts in with -PautoResponder=true (docs/AUTO_RESPONDER.md). Off means no settings entry,
        // no receiver hook and no worker: the code is inert.
        debug {
            buildConfigField("boolean", "AUTO_RESPONDER_ENABLED", (project.findProperty("autoResponder") ?: "true").toString())
        }
        release {
            buildConfigField("boolean", "AUTO_RESPONDER_ENABLED", (project.findProperty("autoResponder") ?: "false").toString())
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    sourceSets {
        // Lets MigrationTestHelper (room-testing) find the exported schema JSON under Robolectric.
        // Debug variant only, so release builds never carry the schema files.
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
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
ksp {
    // Exported Room schemas back the migration tests (see MigrationTest).
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// A store build must carry the real RatingKit, not the no-op stand-in (see settings.gradle.kts).
gradle.taskGraph.whenReady {
    val usesStub = gradle.extra["ratingkitIsStub"] == true
    val releaseTask = allTasks.any { it.project == project && (it.name.contains("Release") && (it.name.startsWith("bundle") || it.name.startsWith("assemble") || it.name.startsWith("publish"))) }
    if (usesStub && releaseTask && project.findProperty("allowRatingKitStub") != "true") {
        throw GradleException("This release build would use ratingkit-stub (the real RatingKit module was not found). Point -Pratingkit.dir at it, or pass -PallowRatingKitStub=true to build without it on purpose.")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation(project(":ratingkit"))
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    // A transitive library pulls androidx.fragment 1.1.0, which lint rejects for registerForActivityResult
    // (backup/restore file pickers). Force a current version.
    implementation("androidx.fragment:fragment:1.8.5")
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

    // WebSocket client for the cloud MCP relay (inboxiq-mcp-backend). `relay` variant ONLY:
    // the core variant must contain no network client at all.
    "relayImplementation"("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.work:work-testing:2.9.1")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
