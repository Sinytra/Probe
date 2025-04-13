package org.sinytra.adapter.game.jar;

import net.minecraftforge.fart.api.ClassProvider;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.sinytra.adapter.patch.util.provider.ClassLookup;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class SimpleClassLookup implements ClassLookup {
    private final ClassProvider upstream;

    private final Map<String, Optional<ClassNode>> classCache = new ConcurrentHashMap<>();

    public SimpleClassLookup(ClassProvider upstream) {
        this.upstream = upstream;
    }

    @Override
    public Optional<ClassNode> getClass(String name) {
        return this.classCache.computeIfAbsent(name, s -> this.upstream.getClassBytes(name)
            .map(data -> {
                ClassReader reader = new ClassReader(data);
                ClassNode node = new ClassNode();
                reader.accept(node, 0);
                return node;
            }));
    }
}
