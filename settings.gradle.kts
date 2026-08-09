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
