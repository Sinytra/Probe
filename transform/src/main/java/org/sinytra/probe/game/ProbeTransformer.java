package org.sinytra.probe.game;

import org.sinytra.adapter.game.jar.JarTransformer;
import org.spongepowered.asm.launch.MixinBootstrap;

import java.nio.file.Path;
import java.util.List;

public class ProbeTransformer {
    private static boolean initialized;

    public boolean transform(Path source, Path target, Path cleanPath, List<Path> classPath) throws Throwable {
        if (!initialized) {
            MixinBootstrap.init();
            initialized = true;
        }
        
        // TODO Resolve JiJ
        Path auditLogPath = target.getParent().resolve("audit_log.txt");
        Path generatedJarPath = target.getParent().resolve("generated.jar");
        
        ProbeRuntimeEnvironment environment = new ProbeRuntimeEnvironment(auditLogPath, cleanPath, generatedJarPath);
        JarTransformer transformer = new JarTransformer(environment);

        JarTransformer.TransformableJar transformableJar = transformer.cacheTransformableJar(source.toFile(), target);

        List<JarTransformer.TransformedFabricModPath> results = transformer.transform(List.of(transformableJar), classPath);
        JarTransformer.TransformedFabricModPath result = results.getFirst();

        return true;
    }
}
