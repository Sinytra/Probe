plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.jib)
}

group = "org.sinytra.probe"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kord.core)
    implementation(libs.kotlinx.serialization.hocon)

    implementation(platform(libs.log4j.bom))
    implementation(libs.log4j.core)
    implementation(libs.log4j.slf4j)

    testImplementation(libs.kotlin.test.junit)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = "ghcr.io/sinytra/probe/discord"
        tags = setOf("latest", version.toString())
        auth { 
            setUsername(providers.environmentVariable("DOCKER_REG_USERNAME"))
            setPassword(providers.environmentVariable("DOCKER_REG_PASSWORD"))
        }
    }
    container {
        mainClass = "org.sinytra.MainKt"
    }
}