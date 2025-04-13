package org.sinytra.probe.game;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import net.minecraftforge.fart.api.ClassProvider;
import org.jetbrains.annotations.Nullable;
import org.sinytra.adapter.game.AdapterRuntimeEnvironment;
import org.sinytra.adapter.game.jar.SimpleClassLookup;
import org.sinytra.adapter.patch.util.provider.ClassLookup;
import org.sinytra.probe.game.runtime.MixinServiceProbe;
import org.spongepowered.asm.service.MixinService;

import java.nio.file.Path;
import java.util.function.Supplier;

public class ProbeRuntimeEnvironment implements AdapterRuntimeEnvironment {
    private static final VersionOverrides VERSION_OVERRIDES = new VersionOverrides();
    private static final DependencyOverrides DEPENDENCY_OVERRIDES = new DependencyOverrides(Path.of("nonexistent"));

    private final Path auditLogPath;
    private final Path cleanPath;
    private final Path generatedJarPath;

    public ProbeRuntimeEnvironment(Path auditLogPath, Path cleanPath, Path generatedJarPath) {
        this.auditLogPath = auditLogPath;
        this.cleanPath = cleanPath;
        this.generatedJarPath = generatedJarPath;
    }

    @Override
    public EnvType getEnvType() {
        return EnvType.CLIENT;
    }

    @Override
    public Path getGeneratedJarPath() {
        return this.generatedJarPath;
    }

    @Override
    public ClassLookup getCleanClassLookup() {
        return new SimpleClassLookup(ClassProvider.fromPaths(this.cleanPath));
    }

    @Override
    public Path getAuditReportPath() {
        return this.auditLogPath;
    }

    @Override
    public VersionOverrides getVersionOverrides() {
        return VERSION_OVERRIDES;
    }

    @Override
    public Supplier<DependencyOverrides> getDependencyOverrides() {
        return () -> DEPENDENCY_OVERRIDES;
    }

    @Override
    public void setGlobalBytecodeLoader(@Nullable ILaunchPluginService.ITransformerLoader loader) {
        MixinServiceProbe service = (MixinServiceProbe) MixinService.getService();
        service.setLoader(loader);
    }
}
