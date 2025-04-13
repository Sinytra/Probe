plugins {
    java
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

group = "org.sinytra.probe"
version = "0.0.1"

val localDev: Configuration by configurations.creating {
    outgoing.artifact(tasks.shadowJar)
}

tasks.shadowJar {
    mergeServiceFiles()
    dependencies {
        exclude(dependency("org.apache.logging.log4j:.*"))
    }
}

repositories {
    mavenCentral()
    maven {
        name = "Sinytra"
        url = uri("https://maven.sinytra.org")
        content {
            includeGroupAndSubgroups("org.sinytra")
        }
    }
    maven {
        name = "NeoForged"
        url = uri("https://maven.neoforged.net/releases")
    }
    maven {
        name = "Mojang"
        url = uri("https://libraries.minecraft.net")
    }
}

dependencies {
    implementation("com.mojang:logging:1.2.7")
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("cpw.mods:modlauncher:11.0.4")
    implementation("cpw.mods:securejarhandler:3.0.8")
    implementation("net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7")
    
    implementation(libs.adapter.definition)
    implementation(libs.adapter.data)
    implementation(libs.fart) {
        isTransitive = false
    }
    implementation(libs.forgified.fabric.loader)
    implementation(libs.access.widener)
}
