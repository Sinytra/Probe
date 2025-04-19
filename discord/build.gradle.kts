plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "org.sinytra.probe"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kord.core)

    implementation(platform(libs.log4j.bom))
    implementation(libs.log4j.core)
    implementation(libs.log4j.slf4j)

    testImplementation(libs.kotlin.test.junit)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(23)
}