plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.jib)
}

group = "org.sinytra.probe"

val dockerImage = "sinytra/probe/discord"

repositories {
    mavenCentral()
    // TODO Remove after stable Kord includes https://github.com/kordlib/kord/pull/1072
    maven {
        name = "MavenCentral Snapshots"
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        content {
            includeGroup("dev.kord")
        }
        mavenContent {
            snapshotsOnly()
        }
    }
}

dependencies {
    implementation(project(":base"))
    implementation(libs.kord.core)
    implementation(libs.kotlinx.serialization.hocon)

    implementation(platform(libs.log4j.bom))
    implementation(libs.log4j.core)
    implementation(libs.log4j.slf4j)

    testImplementation(libs.kotlin.test.junit)
}

tasks {
    test {
        useJUnitPlatform()
    }

    register("publishDocker") {
        dependsOn("jib")
    }
}

kotlin {
    jvmToolchain(25)
}

jib {
    from {
        image = "eclipse-temurin:25-jre"
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
        mainClass = "org.sinytra.MainKt"
    }
}