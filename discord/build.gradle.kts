plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "org.sinytra.probe"
version = "1.0-SNAPSHOT"

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