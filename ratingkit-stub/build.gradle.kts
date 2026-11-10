// Stand-in for the private RatingKit module so the project builds from a plain clone.
// settings.gradle.kts uses the real module when it can find it and this one otherwise.
// It has the same API as the real module and does nothing (no review prompt is ever shown).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kreativekoala.ratingkit"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
