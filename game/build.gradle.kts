import net.neoforged.nfrtgradle.CreateMinecraftArtifacts

plugins {
    alias(libs.plugins.moddev)
    alias(libs.plugins.adapter.userdev)
}

group = "org.sinytra.probe"
version = "0.0.1"

val gameVersion: String by rootProject
val neoForgeVersion: String by rootProject

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

neoForge {
    version = neoForgeVersion
}

ext["cleanPath"] = neoForge.additionalMinecraftArtifacts.get()["vanillaDeobfuscated"]?.absolutePath
ext["transformClassPath"] = listOf(
    tasks.getByName("createMinecraftArtifacts", CreateMinecraftArtifacts::class).compiledArtifact.get().asFile.absolutePath
).joinToString(separator = ";")