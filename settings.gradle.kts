pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // org.fossify:mmslib (MMS PDU send)
    }
}

rootProject.name = "InboxIQ"
include(":app")
include(":gemma_model_pack")
// RatingKit (in-app review prompts) is a separate repository that is not public. A build finds the real module in this
// order: -Pratingkit.dir=<path> or $RATINGKIT_DIR, ~/Documents/GitHub/RatingKit-Android/ratingkit, ../RatingKit-Android/ratingkit.
// If none exists (any plain clone), ratingkit-stub is used: same API, does nothing. -Pratingkit.dir=stub forces the stub.
// Release builds refuse to use the stub unless -PallowRatingKitStub=true, so a store build can never silently lose the prompt.
val ratingkitRequested: String? = providers.gradleProperty("ratingkit.dir").orNull ?: System.getenv("RATINGKIT_DIR")
val ratingkitDir: File? =
    if (ratingkitRequested == "stub") null
    else listOfNotNull(
        ratingkitRequested,
        "${System.getProperty("user.home")}/Documents/GitHub/RatingKit-Android/ratingkit",
        "../RatingKit-Android/ratingkit",
    ).map { file(it) }.firstOrNull { File(it, "build.gradle.kts").isFile }
include(":ratingkit")
project(":ratingkit").projectDir = ratingkitDir ?: file("ratingkit-stub")
gradle.extra["ratingkitIsStub"] = ratingkitDir == null
if (ratingkitDir == null) logger.warn("RatingKit module not found: building with ratingkit-stub (no review prompts).")
