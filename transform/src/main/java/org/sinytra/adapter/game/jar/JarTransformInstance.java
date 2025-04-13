package org.sinytra.adapter.game.jar;

import com.google.common.base.Stopwatch;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.MappingResolverImpl;
import net.minecraftforge.fart.api.ClassProvider;
import net.minecraftforge.fart.api.Renamer;
import net.minecraftforge.fart.internal.EnhancedRemapper;
import net.minecraftforge.srgutils.IMappingFile;
import org.jetbrains.annotations.Nullable;
import org.sinytra.adapter.game.AdapterRuntimeEnvironment;
import org.sinytra.adapter.game.patch.ClassAnalysingTransformer;
import org.sinytra.adapter.game.patch.ClassNodeTransformer;
import org.sinytra.adapter.game.patch.ConnectorRefmapHolder;
import org.sinytra.adapter.game.patch.ReflectionRenamingTransformer;
import org.sinytra.adapter.game.transform.*;
import org.sinytra.adapter.patch.LVTOffsets;
import org.sinytra.adapter.patch.api.Patch;
import org.sinytra.adapter.patch.api.PatchAuditTrail;
import org.sinytra.adapter.patch.api.PatchEnvironment;
import org.sinytra.adapter.patch.transformer.serialization.PatchSerialization;
import org.sinytra.adapter.patch.util.provider.ClassLookup;
import org.sinytra.adapter.patch.util.provider.MixinClassLookup;
import org.slf4j.Logger;

import java.io.*;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class JarTransformInstance {
    private static final String FABRIC_MAPPING_NAMESPACE = "Fabric-Mapping-Namespace";
    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LogUtils.getLogger();

    private final MappingAwareReferenceMapper remapper;
    private final List<? extends Patch> adapterPatches;
    private final LVTOffsets lvtOffsetsData;
    private final BytecodeFixerUpperFrontend bfu;
    private final EnhancedRemapper enhancedRemapper;
    private final ClassLookup cleanClassLookup;
    private final List<Path> libs;
    private final PatchAuditTrail auditTrail;
    private final AdapterRuntimeEnvironment environment;

    public JarTransformInstance(AdapterRuntimeEnvironment environment, ClassProvider classProvider/*, Collection<IModFile> loadedMods*/, List<Path> libs) {
        this.environment = environment;

        MappingResolverImpl resolver = FabricLoaderImpl.INSTANCE.getMappingResolver();
        resolver.getMap(JarTransformer.OBF_NAMESPACE, JarTransformer.SOURCE_NAMESPACE);
        resolver.getMap(JarTransformer.SOURCE_NAMESPACE, JarTransformer.OBF_NAMESPACE);
        this.remapper = new MappingAwareReferenceMapper(resolver.getCurrentMap(JarTransformer.SOURCE_NAMESPACE));

        try {
            URL patchDataPath = JarTransformInstance.class.getResource("/patch_data.json");
            try (Reader reader = new BufferedReader(new InputStreamReader(patchDataPath.openStream()))) {
                JsonElement json = GSON.fromJson(reader, JsonElement.class);
                this.adapterPatches = PatchSerialization.deserialize(json, JsonOps.INSTANCE);
            }
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }

        try {
            URL offsetDataPath = JarTransformInstance.class.getResource("/lvt_offsets.json");
            try (Reader reader = new BufferedReader(new InputStreamReader(offsetDataPath.openStream()))) {
                JsonElement json = GSON.fromJson(reader, JsonElement.class);
                this.lvtOffsetsData = LVTOffsets.fromJson(json);
            }
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }

        IMappingFile mappingFile = FabricLoaderImpl.INSTANCE.getMappingResolver().getCurrentMap(JarTransformer.SOURCE_NAMESPACE);
        ClassProvider intermediaryClassProvider = new OptimizedRenamingTransformer.IntermediaryClassProvider(classProvider, mappingFile, mappingFile.reverse(), s -> {
        });
        this.enhancedRemapper = new OptimizedRenamingTransformer.MixinAwareEnhancedRemapper(intermediaryClassProvider, mappingFile, IntermediateMapping.get(JarTransformer.SOURCE_NAMESPACE), s -> {
        });
        this.cleanClassLookup = environment.getCleanClassLookup();
        this.bfu = new BytecodeFixerUpperFrontend(this.cleanClassLookup, MixinClassLookup.INSTANCE, this.environment.getGeneratedJarPath());
        this.libs = libs;
        this.auditTrail = PatchAuditTrail.create();

//        MixinPatchTransformer.completeSetup(loadedMods);
    }

    public BytecodeFixerUpperFrontend getBfu() {
        return bfu;
    }

    @Nullable
    public PatchAuditTrail transformJar(File input, Path output, JarTransformer.FabricModFileMetadata metadata) throws IOException {
        Stopwatch stopwatch = Stopwatch.createStarted();

        if (metadata.generated()) {
            processGeneratedJar(input, output, stopwatch);
            return null;
        }

        String jarMapping = metadata.manifestAttributes().getValue(FABRIC_MAPPING_NAMESPACE);
        if (jarMapping != null && !jarMapping.equals(JarTransformer.SOURCE_NAMESPACE)) {
            LOGGER.error("Found transformable jar with unsupported mapping {}, currently only {} is supported", jarMapping, JarTransformer.SOURCE_NAMESPACE);
        }

        MappingResolverImpl resolver = FabricLoaderImpl.INSTANCE.getMappingResolver();
        RefmapRemapper.RefmapFiles refmap = RefmapRemapper.processRefmaps(input.toPath(), metadata.refmaps(), this.remapper, this.libs);
        IMappingFile srgToIntermediary = resolver.getMap(JarTransformer.OBF_NAMESPACE, JarTransformer.SOURCE_NAMESPACE);
        IMappingFile intermediaryToSrg = resolver.getCurrentMap(JarTransformer.SOURCE_NAMESPACE);
        AccessorRedirectTransformer accessorRedirectTransformer = new AccessorRedirectTransformer(srgToIntermediary);

        PatchAuditTrail jarTrail = PatchAuditTrail.create();
        List<Patch> extraPatches = Stream.concat(this.adapterPatches.stream(), AccessorRedirectTransformer.PATCHES.stream()).toList();
        ConnectorRefmapHolder refmapHolder = new ConnectorRefmapHolder(refmap.merged(), refmap.files());
        int fabricLVTCompatibility = 11; //FabricMixinBootstrap.MixinConfigDecorator.getMixinCompat(metadata.modMetadata()); TODO
        PatchEnvironment environment = PatchEnvironment.create(refmapHolder, this.cleanClassLookup, this.bfu.unwrap(), fabricLVTCompatibility, jarTrail);
        MixinPatchTransformer patchTransformer = new MixinPatchTransformer(this.environment, this.lvtOffsetsData, environment, extraPatches);
        RefmapRemapper refmapRemapper = new RefmapRemapper(refmap.files());
        Renamer.Builder builder = Renamer.builder()
            .add(new JarSignatureStripper())
            .add(new ClassNodeTransformer(
                new FieldToMethodTransformer(metadata.modMetadata().getAccessWidener(), srgToIntermediary),
                accessorRedirectTransformer,
                new ReflectionRenamingTransformer(intermediaryToSrg, IntermediateMapping.get(JarTransformer.SOURCE_NAMESPACE))
            ))
            .add(new OptimizedRenamingTransformer(this.enhancedRemapper, false, metadata.refmaps().isEmpty()))
            .add(new ClassNodeTransformer(new ClassAnalysingTransformer()))
            .add(patchTransformer)
            .add(refmapRemapper)
            .logger(s -> LOGGER.trace(JarTransformer.TRANSFORM_MARKER, s))
            .debug(s -> LOGGER.trace(JarTransformer.TRANSFORM_MARKER, s))
            .ignoreJarPathPrefix("assets/", "data/");
        if (!metadata.containsAT()) {
            builder.add(new AccessWidenerTransformer(metadata.modMetadata().getAccessWidener(), resolver, IntermediateMapping.get(JarTransformer.SOURCE_NAMESPACE)));
        }
        try (Renamer renamer = builder.build()) {
            accessorRedirectTransformer.analyze(input, metadata.mixinPackages(), environment);

            renamer.run(input, output.toFile());

            try (FileSystem zipFile = FileSystems.newFileSystem(output)) {
                patchTransformer.finalize(zipFile.getPath("/"), metadata.mixinConfigs(), refmap.files(), refmapHolder.getDirtyRefmaps());
            }
        } catch (Throwable t) {
            LOGGER.error("Encountered error while transforming jar file {}", input.getAbsolutePath(), t);
            throw t;
        }

        stopwatch.stop();
        LOGGER.debug(JarTransformer.TRANSFORM_MARKER, "Jar {} transformed in {} ms", input.getName(), stopwatch.elapsed(TimeUnit.MILLISECONDS));

        this.auditTrail.merge(jarTrail);
        return jarTrail;
    }

    private static void processGeneratedJar(File input, Path output, Stopwatch stopwatch) throws IOException {
        Files.copy(input.toPath(), output);
        stopwatch.stop();
        LOGGER.debug(JarTransformer.TRANSFORM_MARKER, "Skipping transformation of jar {} after {} ms as it contains generated metadata, assuming it's a java library", input.getName(), stopwatch.elapsed(TimeUnit.MILLISECONDS));
    }

    public void saveAuditReport() {
        try {
            Path path = this.environment.getAuditReportPath();

            Files.deleteIfExists(path);
            String log = this.auditTrail.getCompleteReport();
            Files.writeString(path, log);
        } catch (IOException e) {
            LOGGER.error("Error writing patch audit report", e);
        }
    }
}
