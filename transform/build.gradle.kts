import com.github.jengelman.gradle.plugins.shadow.transformers.ResourceTransformer
import com.github.jengelman.gradle.plugins.shadow.transformers.TransformerContext
import org.apache.tools.zip.ZipOutputStream
import org.gradle.api.file.FileTreeElement

plugins {
    java
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

group = "org.sinytra.probe"
version = "0.0.1"

repositories {
    mavenCentral()
    maven {
        name = "Sinytra"
        url = uri("https://maven.sinytra.org")
        content {
            includeGroupAndSubgroups("org.sinytra")
        }
    }
    maven {
        name = "NeoForged"
        url = uri("https://maven.neoforged.net/releases")
    }
    maven {
        name = "Mojang"
        url = uri("https://libraries.minecraft.net")
    }
}

dependencies {
    implementation(platform(libs.log4j.bom))
    implementation(libs.log4j.core)
    implementation(libs.log4j.slf4j)
    implementation(libs.adapter.definition)
    implementation(libs.adapter.data)
    implementation(libs.fart) {
        isTransitive = false
    }
    implementation(libs.forgified.fabric.loader)
    implementation(libs.access.widener)

    implementation("com.mojang:logging:1.2.7")
    implementation("cpw.mods:modlauncher:11.0.4")
    implementation("cpw.mods:securejarhandler:3.0.8")
    implementation("net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7")
    implementation("info.picocli:picocli:4.7.7")
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")
}

tasks {
    compileJava {
        options.compilerArgs.add("-Aproject=${project.group}/${project.name}")
    }

    jar {
        manifest {
            attributes(
                "Main-Class" to "org.sinytra.probe.game.cli.Main",
                "Implementation-Version" to project.version
            )
        }
    }

    shadowJar {
        manifest.inheritFrom(jar.get().manifest)
        mergeServiceFiles()
        transform(Log4JConfigTransformer(sourceSets.main.get().output.resourcesDir))
    }
}

class Log4JConfigTransformer(private val resourcesDir: File?) : ResourceTransformer {
  override fun canTransformResource(element: FileTreeElement): Boolean {
      return element.name == "log4j2.xml" && element.file.parentFile != resourcesDir
  }
  override fun transform(context: TransformerContext) {}
  override fun hasTransformedResource(): Boolean = true
  override fun modifyOutputStream(os: ZipOutputStream, preserveFileTimestamps: Boolean) {}
}