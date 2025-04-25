plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "org.sinytra.probe"
version = "0.0.1"

val neoForgeVersion: String by rootProject
val gameVersion: String by rootProject

val transfomer: Configuration by configurations.creating {
    attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
    isTransitive = false
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

application {
    mainClass = "io.ktor.server.netty.EngineMain"

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = mutableListOf(
        "-Dio.ktor.development=$isDevelopment",
        "-Dorg.sinytra.probe.storage_path=${file("run").absolutePath}",
        "-Dorg.sinytra.probe.neo_version=$neoForgeVersion",
        "-Dorg.sinytra.probe.game_version=$gameVersion",
        "-Dorg.sinytra.probe.toolchain_version=0.0.1",
        "-Dorg.sinytra.probe.local_cache=true",
        "-Dorg.probe.logging.level=DEBUG",
        "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED"
    )
}

ktor {
    docker {
        localImageName.set("sinytra/probe/core")
        imageTag.set(version as String)
    }
}

repositories {
    mavenCentral()
    mavenLocal { 
        content { 
            includeGroup("org.sinytra.connector")
        }
    }
}

afterEvaluate {
    (application.applicationDefaultJvmArgs as MutableList<String>) += listOf("-Dorg.sinytra.transformer.path=${transfomer.singleFile.absolutePath}")
}

dependencies {
    transfomer("org.sinytra.connector:transformer:2.0.0-beta.8+1.21.1+dev-g20c90a4")

    implementation(platform(libs.log4j.bom))
    implementation(libs.log4j.core)
    implementation(libs.log4j.slf4j)

    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.postgresql)
    implementation(libs.h2)
    implementation(libs.lettuce)
    implementation(libs.ktor.server.netty)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.datetime)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.resources)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}

tasks.getByName("run") {
    dependsOn(transfomer)
}