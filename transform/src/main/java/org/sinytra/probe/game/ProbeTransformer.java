package org.sinytra.probe.game;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import cpw.mods.jarhandling.SecureJar;
import org.sinytra.adapter.game.jar.JarInspector;
import org.sinytra.adapter.game.jar.JarTransformer;
import org.sinytra.adapter.game.util.ConnectorFabricModMetadata;
import org.sinytra.adapter.game.util.ConnectorUtil;
import org.sinytra.probe.game.discovery.ProbeModDiscoverer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.launch.MixinBootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static cpw.mods.modlauncher.api.LambdaExceptionUtils.rethrowFunction;

public class ProbeTransformer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProbeTransformer.class);
    private static boolean initialized;

    public record ModPathTuple(List<Path> fabric, List<Path> other) {
    }

    public record TransformOutput(boolean success, String primaryModid) {
    }

    public TransformOutput transform(List<Path> sources, Path primarySource, Path cleanPath, List<Path> classPath, String gameVersion) throws Throwable {
        if (!initialized) {
            MixinBootstrap.init();
            initialized = true;
        }

        Path outputDir = primarySource.getParent().resolve(".output");
        Files.createDirectories(outputDir);
        Path tempDir = outputDir.resolve("temp");
        Files.createDirectories(tempDir);

        Path auditLogPath = outputDir.resolve("audit_log.txt");
        Path generatedJarPath = outputDir.resolve("generated.jar");

        ProbeRuntimeEnvironment environment = new ProbeRuntimeEnvironment(outputDir, auditLogPath, cleanPath, generatedJarPath, gameVersion);
        JarTransformer transformer = new JarTransformer(environment);
        JarInspector inspector = new JarInspector(transformer, tempDir);

        ModPathTuple jars = filterFabricJars(sources);

        List<JarTransformer.TransformableJar> discoveredJars = jars.fabric().stream()
            .map(rethrowFunction(src -> transformer.cacheTransformableJar(src.toFile())))
            .toList();

        JarTransformer.TransformableJar primaryJar = discoveredJars.stream()
            .filter(j -> j.input().toPath().equals(primarySource))
            .findFirst()
            .orElseThrow();
        String primaryModid = primaryJar.modPath().metadata().modMetadata().getId();

        // Prepare jars
        Multimap<JarTransformer.TransformableJar, JarTransformer.TransformableJar> parentToChildren = HashMultimap.create();
        Stream<JarTransformer.TransformableJar> discoveredNestedJars = discoveredJars.stream()
            .flatMap(jar -> {
                ConnectorFabricModMetadata metadata = jar.modPath().metadata().modMetadata();
                return inspector.discoverNestedJarsRecursive(jar, metadata.getJars(), parentToChildren, List.of(), List.of());
            });
        List<JarTransformer.TransformableJar> allJars = Stream.concat(discoveredJars.stream(), discoveredNestedJars).toList();

        List<Path> resolvedClassPath = new ArrayList<>(ProbeModDiscoverer.resolveClassPath(classPath));
        resolvedClassPath.addAll(jars.other());

        // Run transformation
        try {
            List<JarTransformer.TransformedFabricModPath> results = transformer.transform(allJars, resolvedClassPath);
            JarTransformer.TransformedFabricModPath result = results.getFirst();

            boolean success = result.auditTrail() == null || !result.auditTrail().hasFailingMixins();
            return new TransformOutput(success, primaryModid);
        } catch (Throwable t) {
            LOGGER.error("Failed to transform sources", t);
            return new TransformOutput(false, primaryModid);
        }
    }

    public ModPathTuple filterFabricJars(List<Path> paths) {
        List<Path> fabricJars = new ArrayList<>();
        List<Path> otherJars = new ArrayList<>();

        for (Path path : paths) {
            SecureJar jar = SecureJar.from(path);
            if (Files.exists(jar.getPath(ConnectorUtil.FABRIC_MOD_JSON))) {
                fabricJars.add(path);
            } else {
                otherJars.add(path);
            }
        }

        return new ModPathTuple(fabricJars, otherJars);
    }
}
