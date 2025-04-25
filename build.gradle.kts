plugins {
    alias(libs.plugins.kotlin.jvm)
    id("net.neoforged.gradleutils") version "3.0.0"
}

group = "org.sinytra.probe"
version = gradleutils.version

val CI: Provider<String> = providers.environmentVariable("CI")
if (!CI.isPresent) {
    version = "$version+dev-${gradleutils.gitInfo["hash"]}"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(23)
}
