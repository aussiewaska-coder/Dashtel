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
    }
}
plugins {
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("com.android.application") version "8.13.1" apply false
    id("com.google.gms.google-services") version "4.4.3" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
}
rootProject.name = "twilio-dialer"
include(":app")
