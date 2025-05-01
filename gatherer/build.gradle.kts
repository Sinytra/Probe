plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jib)
}

group = "org.sinytra.probe"

val neoForgeVersion: String by rootProject
val gameVersion: String by rootProject
val compatibleGameVersions: String by rootProject
val nfrtVersion: String by rootProject

kotlin {
    jvmToolchain(21)
}

val transfomer: Configuration by configurations.creating

repositories {
    mavenCentral()
    mavenLocal {
        content {
            includeGroup("org.sinytra.connector")
        }
    }
}

dependencies {
    implementation(project(":core"))
    transfomer(libs.connector.tranformer)

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

    implementation("info.picocli:picocli:4.7.7")
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")
}

jib {
    from {
        image = "eclipse-temurin:21-jdk"
    }
    to {
        image = "sinytra/probe/gatherer"
        tags = setOf("latest", version.toString())
        auth { 
            setUsername(providers.environmentVariable("DOCKER_REG_USERNAME"))
            setPassword(providers.environmentVariable("DOCKER_REG_PASSWORD"))
        }
    }
    container {
        val transformerVersion = libs.connector.tranformer.get().version!!

        mainClass = "org.sinytra.probe.gatherer.RunnerEntrypointKt"
        args = listOf(
            "--nfrt-version", nfrtVersion,
            "--neoforge-version", neoForgeVersion,
            "--toolchain-version", transformerVersion,
            "--game-version", gameVersion,
            "--work-dir", "/probe"
        ) + compatibleGameVersions
            .split(",")
            .flatMap { listOf("--compatible-version", it) } + "run"
    }
}
