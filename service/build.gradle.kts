plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "org.sinytra.probe"

val gameVersion: String by rootProject
val dockerImage = "sinytra/probe/service"

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

application {
    mainClass = "org.sinytra.probe.service.ApplicationKt"

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = mutableListOf(
        "-Dio.ktor.development=$isDevelopment",
        "-Dorg.sinytra.probe.storage_path=${file("run").absolutePath}",
        "-Dorg.sinytra.probe.game_version=$gameVersion",
        "-Dorg.sinytra.probe.local_cache=true",
        "-Dorg.probe.logging.level=DEBUG",
        "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED"
    )
}

ktor {
    docker {
        customBaseImage = "eclipse-temurin:21-jdk" // JDK is required for NFRT
        localImageName.set(dockerImage)
        imageTag.set(version.toString())
        externalRegistry.set(
            io.ktor.plugin.features.DockerImageRegistry.externalRegistry(
                hostname = providers.environmentVariable("DOCKER_REGISTRY").orElse("ghcr.io"),
                username = providers.environmentVariable("DOCKER_REG_USERNAME"),
                password = providers.environmentVariable("DOCKER_REG_PASSWORD"),
                project = provider { dockerImage }
            )
        )
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.log4j.bom))
    implementation(libs.log4j.core)
    implementation(libs.log4j.slf4j)

    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.openapi)
    implementation(libs.ktor.swagger.ui)
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

tasks {
    register("publishDocker") {
        dependsOn("publishImage")
    }
}