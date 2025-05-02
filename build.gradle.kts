plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.gradleutils)
}

group = "org.sinytra.probe"
version = gradleutils.version

val CI: Provider<String> = providers.environmentVariable("CI")
if (!CI.isPresent) {
    version = "$version.dev-${gradleutils.gitInfo["hash"]}"
}

allprojects {
    version = rootProject.version
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
