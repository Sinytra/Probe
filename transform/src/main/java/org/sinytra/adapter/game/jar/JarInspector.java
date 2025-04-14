package org.sinytra.adapter.game.jar;

import com.google.common.collect.Multimap;
import cpw.mods.jarhandling.SecureJar;
import net.fabricmc.loader.impl.metadata.NestedJarEntry;
import org.sinytra.adapter.game.util.ConnectorFabricModMetadata;
import org.sinytra.adapter.game.util.ConnectorUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.stream.Stream;

import static cpw.mods.modlauncher.api.LambdaExceptionUtils.uncheck;

public class JarInspector {
    private final JarTransformer transformer;
    private final Path tempDir;

    public JarInspector(JarTransformer transformer, Path tempDir) {
        this.transformer = transformer;
        this.tempDir = tempDir;
    }

    public Stream<JarTransformer.TransformableJar> discoverNestedJarsRecursive(
        JarTransformer.TransformableJar parent,
        Collection<NestedJarEntry> jars,
        Multimap<JarTransformer.TransformableJar, JarTransformer.TransformableJar> parentToChildren,
        Collection<String> loadedModIds,
        Collection<String> loadedModuleNames
    ) {
        SecureJar secureJar = SecureJar.from(parent.input().toPath());
        return jars.stream()
            .map(entry -> secureJar.getPath(entry.getFile()))
            .filter(Files::exists)
            .flatMap(path -> {
                JarTransformer.TransformableJar jar = uncheck(() -> prepareNestedJar(secureJar.getPrimaryPath().getFileName().toString(), path));
//                if (shouldIgnoreMod(jar, loadedModIds, loadedModuleNames)) {
//                    return Stream.empty();
//                }
                parentToChildren.put(parent, jar);
                ConnectorFabricModMetadata metadata = jar.modPath().metadata().modMetadata();
                return Stream.concat(Stream.of(jar), discoverNestedJarsRecursive(jar, metadata.getJars(), parentToChildren, loadedModIds, loadedModuleNames));
            });
    }

    private JarTransformer.TransformableJar prepareNestedJar(String parentName, Path path) {
        String parentNameWithoutExt = parentName.split("\\.(?!.*\\.)")[0];
        // Extract JiJ
        Path extracted = this.tempDir.resolve(parentNameWithoutExt + "$" + path.getFileName().toString());
        ConnectorUtil.cache(path, extracted, () -> Files.copy(path, extracted)); // TODO Cache service?

        return uncheck(() -> this.transformer.cacheTransformableJar(extracted.toFile()));
    }
}
