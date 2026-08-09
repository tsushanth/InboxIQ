plugins {
    id("com.android.application") version "8.13.2" apply false
    // Bumped from 1.9.24 — litertlm-android 0.15.0's Kotlin metadata requires >= 2.3.0 to read.
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    id("com.google.devtools.ksp") version "2.3.0" apply false
    id("com.android.asset-pack") version "8.13.2" apply false
}
