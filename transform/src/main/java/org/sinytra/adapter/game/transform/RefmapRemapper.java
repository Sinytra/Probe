package org.sinytra.adapter.game.transform;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fart.api.Transformer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.*;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RefmapRemapper implements Transformer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final String INTERMEDIARY_MAPPING_ENV = "named:intermediary";
    private static final String MOJ_MAPPING_ENV = "mojang";

    public record RefmapFiles(MappingAwareReferenceMapper.SimpleRefmap merged, Map<String, MappingAwareReferenceMapper.SimpleRefmap> files) {}

    public static RefmapFiles processRefmaps(Path input, Collection<String> refmaps, MappingAwareReferenceMapper remapper, List<Path> libs) throws IOException {
        MappingAwareReferenceMapper.SimpleRefmap results = new MappingAwareReferenceMapper.SimpleRefmap(Map.of(), Map.of());
        Map<String, MappingAwareReferenceMapper.SimpleRefmap> refmapFiles = new HashMap<>();
        try (FileSystem fs = FileSystems.newFileSystem(input)) {
            for (String refmap : refmaps) {
                Path refmapPath = fs.getPath(refmap);
                if (Files.notExists(refmapPath)) {
                    refmapPath = findRefmapOnClasspath(refmap, input, libs);
                }
                if (refmapPath != null) {
                    byte[] data = Files.readAllBytes(refmapPath);
                    MappingAwareReferenceMapper.SimpleRefmap remapped = remapRefmapInPlace(data, remapper);
                    refmapFiles.put(refmap, remapped);
                    results = results.merge(remapped);
                }
                else {
                    LOGGER.warn("Refmap remapper could not find refmap file {}", refmap);
                }
            }
        }
        return new RefmapFiles(results, refmapFiles);
    }

    @Nullable
    private static Path findRefmapOnClasspath(String resource, Path exclude, List<Path> libs) {
        for (Path lib : libs) {
            if (lib == exclude) {
                continue;
            }
            Path basePath;
            if (Files.isDirectory(lib)) {
                basePath = lib;
            } else {
                try {
                    FileSystem fs = FileSystems.newFileSystem(lib);
                    basePath = fs.getPath("");
                } catch (Exception e) {
                    LOGGER.error("Error opening jar file", e);
                    return null;
                }
            }
            Path path = basePath.resolve(resource);
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }

    private final Map<String, MappingAwareReferenceMapper.SimpleRefmap> files;

    public RefmapRemapper(Map<String, MappingAwareReferenceMapper.SimpleRefmap> files) {
        this.files = files;
    }

    @Override
    public ResourceEntry process(ResourceEntry entry) {
        String name = entry.getName();
        if (this.files.containsKey(name)) {
            try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream()) {
                try (Writer writer = new OutputStreamWriter(byteStream)) {
                    this.files.get(name).write(writer);
                    writer.flush();
                }
                byte[] data = byteStream.toByteArray();
                return ResourceEntry.create(name, entry.getTime(), data);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return entry;
    }

    private static MappingAwareReferenceMapper.SimpleRefmap remapRefmapInPlace(byte[] data, MappingAwareReferenceMapper remapper) {
        Reader reader = new InputStreamReader(new ByteArrayInputStream(data));
        MappingAwareReferenceMapper.SimpleRefmap simpleRefmap = GSON.fromJson(reader, MappingAwareReferenceMapper.SimpleRefmap.class);
        Map<String, String> replacements = Map.of(INTERMEDIARY_MAPPING_ENV, MOJ_MAPPING_ENV);
        return remapper.remap(simpleRefmap, replacements);
    }
}
