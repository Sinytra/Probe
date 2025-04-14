package org.sinytra.probe.game;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.sinytra.adapter.game.jar.JarInspector;
import org.sinytra.adapter.game.jar.JarTransformer;
import org.sinytra.adapter.game.util.ConnectorFabricModMetadata;
import org.spongepowered.asm.launch.MixinBootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static cpw.mods.modlauncher.api.LambdaExceptionUtils.rethrowFunction;

public class ProbeTransformer {
    private static boolean initialized;

    public boolean transform(List<Path> sources, Path primarySource, Path cleanPath, List<Path> classPath, String gameVersion) throws Throwable {
        if (!initialized) {
            MixinBootstrap.init();
            initialized = true;
        }

        Path outputDir = primarySource.getParent().resolve(".output");
        Files.createDirectories(outputDir);
        Path tempDir = outputDir.resolve("temp");
        Files.createDirectories(tempDir);

        // This crashes now because we can't filter out NeoForge mods yet
        // TODO Dont transform neoforge mods
        // TODO Include FFAPI, FFLoader
        Path auditLogPath = outputDir.resolve("audit_log.txt");
        Path generatedJarPath = outputDir.resolve("generated.jar");

        ProbeRuntimeEnvironment environment = new ProbeRuntimeEnvironment(outputDir, auditLogPath, cleanPath, generatedJarPath, gameVersion);
        JarTransformer transformer = new JarTransformer(environment);
        JarInspector inspector = new JarInspector(transformer, tempDir);

        List<JarTransformer.TransformableJar> discoveredJars = sources.stream()
            .map(rethrowFunction(src -> transformer.cacheTransformableJar(src.toFile())))
            .toList();

        // Prepare jars
        Multimap<JarTransformer.TransformableJar, JarTransformer.TransformableJar> parentToChildren = HashMultimap.create();
        Stream<JarTransformer.TransformableJar> discoveredNestedJars = discoveredJars.stream()
            .flatMap(jar -> {
                ConnectorFabricModMetadata metadata = jar.modPath().metadata().modMetadata();
                return inspector.discoverNestedJarsRecursive(jar, metadata.getJars(), parentToChildren, List.of(), List.of());
            });
        List<JarTransformer.TransformableJar> allJars = Stream.concat(discoveredJars.stream(), discoveredNestedJars).toList();

        // Run transformation
        List<JarTransformer.TransformedFabricModPath> results = transformer.transform(allJars, classPath);
        JarTransformer.TransformedFabricModPath result = results.getFirst();

        return result.auditTrail() == null || !result.auditTrail().hasFailingMixins();
    }
}
