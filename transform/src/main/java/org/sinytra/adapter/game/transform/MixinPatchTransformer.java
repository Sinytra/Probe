package org.sinytra.adapter.game.transform;

import com.google.common.collect.ImmutableList;
import com.google.gson.*;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fart.api.Transformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.sinytra.adapter.game.AdapterRuntimeEnvironment;
import org.sinytra.adapter.game.patch.EnvironmentStripperTransformer;
import org.sinytra.adapter.game.util.ConnectorUtil;
import org.sinytra.adapter.patch.LVTOffsets;
import org.sinytra.adapter.patch.api.*;
import org.sinytra.adapter.patch.fixes.FieldTypePatchTransformer;
import org.sinytra.adapter.patch.fixes.FieldTypeUsageTransformer;
import org.sinytra.adapter.patch.transformer.dynamic.*;
import org.sinytra.adapter.patch.transformer.dynfix.DynamicInjectionPointPatch;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static cpw.mods.modlauncher.api.LambdaExceptionUtils.rethrowConsumer;

public class MixinPatchTransformer implements Transformer {
    private static final List<Patch> PRIORITY_PATCHES = MixinPatches.getPriorityPatches();
    private static final List<Patch> PATCHES = MixinPatches.getPatches();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean completedSetup = false;

    // Applied to non-mixins
    private final List<ClassTransform> classTransforms;
    // Applied to mixins only
    private final Patch classPatch;

    private final PatchEnvironment environment;
    private final List<? extends Patch> patches;

    public MixinPatchTransformer(AdapterRuntimeEnvironment runtimeEnvironment, LVTOffsets lvtOffsets, PatchEnvironment environment, List<? extends Patch> adapterPatches) {
        this.classTransforms = List.of(
            new EnvironmentStripperTransformer(runtimeEnvironment.getEnvType()),
            new FieldTypeUsageTransformer()
        );
        this.classPatch = Patch.builder()
            .transform(classTransforms)
            .build();
        
        this.environment = environment;
        this.patches = ImmutableList.<Patch>builder()
            .addAll(PRIORITY_PATCHES)
            .addAll(adapterPatches)
            .addAll(PATCHES)
            .add(
                Patch.builder()
                    .transform(new DynamicInjectorOrdinalPatch())
                    .transform(new DynamicLVTPatch(() -> lvtOffsets))
                    .transform(new DynamicAnonymousShadowFieldTypePatch())
                    .transform(new DynamicModifyVarAtReturnPatch())
                    .transform(new DynamicInheritedInjectionPointPatch())
                    .transform(new DynamicInjectionPointPatch())
                    .build(),
                Patch.interfaceBuilder()
                    .transform(new FieldTypePatchTransformer())
                    .build()
            )
            .build();
    }

    public void finalize(Path zipRoot, Collection<String> configs, Map<String, MappingAwareReferenceMapper.SimpleRefmap> refmapFiles, Set<String> dirtyRefmaps) throws IOException {
        Map<String, MixinClassGenerator.GeneratedClass> generatedMixinClasses = this.environment.classGenerator().getGeneratedMixinClasses();
        if (!generatedMixinClasses.isEmpty()) {
            for (String config : configs) {
                Path entry = zipRoot.resolve(config);
                if (Files.exists(entry)) {
                    try (Reader reader = Files.newBufferedReader(entry)) {
                        JsonElement element = JsonParser.parseReader(reader);
                        JsonObject json = element.getAsJsonObject();
                        if (json.has("package")) {
                            String pkg = json.get("package").getAsString();
                            Map<String, MixinClassGenerator.GeneratedClass> mixins = getMixinsInPackage(pkg, generatedMixinClasses);
                            if (!mixins.isEmpty()) {
                                JsonArray jsonMixins = json.has("mixins") ? json.get("mixins").getAsJsonArray() : new JsonArray();
                                LOGGER.info("Adding {} mixins to config {}", mixins.size(), config);
                                mixins.keySet().forEach(jsonMixins::add);
                                json.add("mixins", jsonMixins);

                                String output = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(json);
                                Files.writeString(entry, output, StandardCharsets.UTF_8);

                                // Update refmap
                                if (json.has("refmap")) {
                                    String refmapName = json.get("refmap").getAsString();
                                    if (dirtyRefmaps.contains(refmapName)) {
                                        MappingAwareReferenceMapper.SimpleRefmap refmap = refmapFiles.get(refmapName);
                                        Path path = zipRoot.resolve(refmapName);
                                        if (Files.exists(path)) {
                                            String refmapString = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(refmap);
                                            Files.writeString(path, refmapString, StandardCharsets.UTF_8);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
        }
        // Strip unused service providers
        Path services = zipRoot.resolve("META-INF/services");
        if (Files.exists(services)) {
            try (Stream<Path> stream = Files.walk(services)) {
                stream
                    .filter(Files::isRegularFile)
                    .forEach(rethrowConsumer(path -> {
                        String serviceName = path.getFileName().toString();
                        List<String> providers = Files.readAllLines(path);
                        List<String> existingProviders = providers.stream()
                            .filter(cls -> Files.exists(zipRoot.resolve(cls.replace('.', '/') + ".class")))
                            .toList();
                        int diff = providers.size() - existingProviders.size();
                        if (diff > 0) {
                            LOGGER.debug("Removing {} nonexistent service providers for service {}", diff, serviceName);
                            if (existingProviders.isEmpty()) {
                                Files.delete(path);
                            } else {
                                String newText = String.join("\n", existingProviders);
                                Files.writeString(path, newText, StandardCharsets.UTF_8);
                            }
                        }
                    }));
            }
        }
    }

    private Map<String, MixinClassGenerator.GeneratedClass> getMixinsInPackage(String mixinPackage, Map<String, MixinClassGenerator.GeneratedClass> generatedMixinClasses) {
        Map<String, MixinClassGenerator.GeneratedClass> classes = new HashMap<>();
        for (Map.Entry<String, MixinClassGenerator.GeneratedClass> entry : generatedMixinClasses.entrySet()) {
            String name = entry.getKey();
            String className = name.replace('/', '.');
            if (className.startsWith(mixinPackage)) {
                String specificPart = className.substring(mixinPackage.length() + 1);
                classes.put(specificPart, entry.getValue());
                generatedMixinClasses.remove(name);
            }
        }
        return classes;
    }

//    public static void completeSetup(Collection<IModFile> mods) {
//        if (completedSetup) {
//            return;
//        }
//        // Injection point data extracted from coremods/method_redirector.js
//        String[] targetClasses = mods.stream()
//            .filter(m -> m.getModFileInfo() != null && !m.getModInfos().isEmpty() && m.getModInfos().get(0).getModId().equals(ConnectorUtil.NEOFORGE_MODID))
//            .map(m -> m.findResource("coremods/finalize_spawn_targets.json"))
//            .filter(Files::exists)
//            .map(rethrowFunction(path -> {
//                try (Reader reader = Files.newBufferedReader(path)) {
//                    return JsonParser.parseReader(reader);
//                }
//            }))
//            .filter(JsonElement::isJsonArray)
//            .flatMap(json -> json.getAsJsonArray().asList().stream()
//                .map(JsonElement::getAsString))
//            .toArray(String[]::new);
//        if (targetClasses.length > 0) {
//            /*PATCHES.add(Patch.builder()
//                .targetClass(targetClasses)
//                .targetInjectionPoint("m_6518_(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/MobSpawnType;Lnet/minecraft/world/entity/SpawnGroupData;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/entity/SpawnGroupData;")
//                .modifyInjectionPoint("Lnet/minecraftforge/event/ForgeEventFactory;onFinalizeSpawn(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/MobSpawnType;Lnet/minecraft/world/entity/SpawnGroupData;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/entity/SpawnGroupData;")
//                .build());*/
//        }
//        completedSetup = true;
//    }

    @Override
    public ClassEntry process(ClassEntry entry) {
        Patch.Result patchResult = Patch.Result.PASS;

        ClassReader reader = new ClassReader(entry.getData());
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        // Some mods generate their mixin configs at runtime, therefore we must scan all classes
        // regardless of whether they're listed in present config files (see Andromeda)
        if (isMixinClass(node)) {
            patchResult = patchResult.or(classPatch.apply(node, this.environment));

            for (Patch patch : this.patches) {
                patchResult = patchResult.or(patch.apply(node, this.environment));
            }
        } else {
            for (ClassTransform transform : classTransforms) {
                patchResult = patchResult.or(transform.apply(node, null, PatchContext.create(node, List.of(), this.environment)));
            }
        }

        // TODO if a mixin method is extracted, roll back the status from compute frames to apply,
        // Alternatively, change the order of patches so that extractmixin comes first
        if (patchResult != Patch.Result.PASS) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | (patchResult == Patch.Result.COMPUTE_FRAMES ? ClassWriter.COMPUTE_FRAMES : 0));
            node.accept(writer);
            return ClassEntry.create(entry.getName(), entry.getTime(), writer.toByteArray());
        }
        return entry;
    }

    @Override
    public Collection<? extends Entry> getExtras() {
        List<Entry> entries = new ArrayList<>();
        List<Patch> patches = ImmutableList.<Patch>builder()
            .add(
                Patch.builder()
                    .transform(new DynamicInheritedInjectionPointPatch())
                    .build()
            ).build();
        this.environment.classGenerator().getGeneratedMixinClasses().forEach((name, cls) -> {
            for (Patch patch : patches) {
                patch.apply(cls.node(), this.environment);
            }

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            cls.node().accept(writer);
            byte[] bytes = writer.toByteArray();
            entries.add(ClassEntry.create(name + ".class", ConnectorUtil.ZIP_TIME, bytes));
        });
        return entries;
    }

    private static boolean isMixinClass(ClassNode classNode) {
        if (classNode.invisibleAnnotations != null) {
            for (AnnotationNode annotation : classNode.invisibleAnnotations) {
                if (annotation.desc.equals(MixinConstants.MIXIN)) {
                    return true;
                }
            }
        }
        return false;
    }
}
