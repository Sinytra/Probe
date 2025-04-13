package org.sinytra.adapter.game;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import org.jetbrains.annotations.Nullable;
import org.sinytra.adapter.patch.util.provider.ClassLookup;

import java.nio.file.Path;
import java.util.function.Supplier;

public interface AdapterRuntimeEnvironment {
    Path getAuditReportPath();
    Path getGeneratedJarPath();
    ClassLookup getCleanClassLookup();
    
    EnvType getEnvType();

    VersionOverrides getVersionOverrides();
    Supplier<DependencyOverrides> getDependencyOverrides();

    void setGlobalBytecodeLoader(@Nullable ILaunchPluginService.ITransformerLoader loader);
}
