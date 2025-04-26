plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "org.sinytra.probe"

val neoForgeVersion: String by rootProject
val gameVersion: String by rootProject

val transfomer: Configuration by configurations.creating {
    attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
    isTransitive = false
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

application {
    mainClass = "org.sinytra.probe.ApplicationKt"

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = mutableListOf(
        "-Dio.ktor.development=$isDevelopment",
        "-Dorg.sinytra.probe.storage_path=${file("run").absolutePath}",
        "-Dorg.sinytra.probe.neo_version=$neoForgeVersion",
        "-Dorg.sinytra.probe.game_version=$gameVersion",
        "-Dorg.sinytra.probe.local_cache=true",
        "-Dorg.probe.logging.level=DEBUG",
        "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED"
    )
}

ktor {
    docker {
        localImageName.set("sinytra/probe/core")
        imageTag.set(version as String)
        externalRegistry.set(
            io.ktor.plugin.features.DockerImageRegistry.externalRegistry(
                username = providers.environmentVariable("DOCKER_REG_USERNAME"),
                password = providers.environmentVariable("DOCKER_REG_PASSWORD"),
                project = provider { "sinytra/probe/core" },
                hostname = provider { "ghcr.io" }
            )
        )
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
    (application.applicationDefaultJvmArgs as MutableList<String>) += listOf(
        "-Dorg.sinytra.transformer.path=${transfomer.singleFile.absolutePath}",
        "-Dorg.sinytra.probe.transformer_version=${libs.connector.tranformer.get().version!!}",
    )
}

dependencies {
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
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}

tasks.getByName("run") {
    dependsOn(transfomer)
}