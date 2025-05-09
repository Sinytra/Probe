pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = "Sinytra"
            url = uri("https://maven.sinytra.org")
        }
    }

    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
    }
}

rootProject.name = "Probe"

include("core")
include("discord")
include("gatherer")
include("service")
