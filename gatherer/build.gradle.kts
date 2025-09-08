plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.jib)
    application
}

group = "org.sinytra.probe"

val neoForgeVersion: String by rootProject
val gameVersion: String by rootProject
val compatibleGameVersions: String by rootProject
val nfrtVersion: String by rootProject
val dockerImage = "sinytra/probe/gatherer"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "org.sinytra.probe.gatherer.RunnerEntrypointKt"

    applicationDefaultJvmArgs = listOf(
        "-Dorg.sinytra.probe.version=$version"
    )
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://jitpack.io")
        content {
            includeGroup("com.github.Steppschuh")
        }
    }
}

dependencies {
    implementation(project(":base"))
    implementation(project(":core"))

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

    implementation(libs.markdown.generator)
    implementation(libs.picocli)
    annotationProcessor(libs.picocli.codegen)
}

tasks {
    named<JavaExec>("run") {
        val transformerVersion = libs.connector.tranformer.get().version!!

        args("run")
        environment(
            "NFRT_VERSION" to nfrtVersion,
            "NEOFORGE_VERSION" to neoForgeVersion,
            "TOOLCHAIN_VERSION" to transformerVersion,
            "GAME_VERSION" to gameVersion,
            "WORK_DIR" to file("run").absolutePath,
            "COMPATIBLE_VERSIONS" to compatibleGameVersions,

            "REDIS_URL" to "redis://localhost:6379/0",
            "TEST_COUNT" to 10
        )
    }
    
    jar {
        manifest {
            attributes(
                "Implementation-Version" to project.version,
            )
        }
    }

    register("publishDocker") {
        dependsOn("jib")
    }
}

jib {
    from {
        image = "eclipse-temurin:21-jdk"
    }
    to {
        setImage(
            providers.environmentVariable("DOCKER_REGISTRY")
                .map { "$it/$dockerImage" }
                .orElse(dockerImage)
        )
        tags = setOf("latest", version.toString())
        auth {
            setUsername(providers.environmentVariable("DOCKER_REG_USERNAME"))
            setPassword(providers.environmentVariable("DOCKER_REG_PASSWORD"))
        }
    }
    container {
        val transformerVersion = libs.connector.tranformer.get().version!!

        mainClass = "org.sinytra.probe.gatherer.RunnerEntrypointKt"
        jvmFlags = listOf(
            "-Dorg.sinytra.probe.version=$version"
        )

        environment = mapOf(
            "NFRT_VERSION" to nfrtVersion,
            "NEOFORGE_VERSION" to neoForgeVersion,
            "TOOLCHAIN_VERSION" to transformerVersion,
            "GAME_VERSION" to gameVersion,
            "WORK_DIR" to "/probe",
            "COMPATIBLE_VERSIONS" to compatibleGameVersions
        )
    }
}
