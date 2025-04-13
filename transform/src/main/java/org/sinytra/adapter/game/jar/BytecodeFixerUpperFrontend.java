package org.sinytra.adapter.game.jar;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;
import org.sinytra.adapter.game.util.ConnectorUtil;
import org.sinytra.adapter.patch.fixes.BytecodeFixerUpper;
import org.sinytra.adapter.patch.fixes.SimpleTypeAdapter;
import org.sinytra.adapter.patch.fixes.TypeAdapter;
import org.sinytra.adapter.patch.util.provider.ClassLookup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;

public class BytecodeFixerUpperFrontend {
    private static final List<TypeAdapter> FIELD_TYPE_ADAPTERS = List.of(
        new SimpleTypeAdapter(Type.getObjectType("net/minecraft/core/Holder$Reference"), Type.getObjectType("java/lang/Object"), (list, insn) ->
            list.insert(insn, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/core/Holder$Reference", "value", "()Ljava/lang/Object;"))),
        new SimpleTypeAdapter(Type.getObjectType("net/minecraft/resources/ResourceLocation"), Type.getObjectType("java/lang/String"), (list, insn) ->
            list.insert(insn, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/resources/ResourceLocation", "toString", "()Ljava/lang/String;"))),
        new SimpleTypeAdapter(Type.getObjectType("net/minecraft/world/item/ItemStack"), Type.getObjectType("net/minecraft/world/item/Item"), (list, insn) ->
            list.insert(insn, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/item/ItemStack", "getItem", "()Lnet/minecraft/world/item/Item;"))),
//        new SimpleTypeAdapter(Type.getObjectType("java/util/List"), Type.getType("[Lnet/minecraft/world/level/storage/loot/LootPool;"), (list, insn) -> {
//            list.insert(insn, ASMAPI.listOf(
//                new InsnNode(Opcodes.ICONST_0),
//                new TypeInsnNode(Opcodes.ANEWARRAY, "net/minecraft/world/level/storage/loot/LootPool"),
//                new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;", true),
//                new TypeInsnNode(Opcodes.CHECKCAST, "[Lnet/minecraft/world/level/storage/loot/LootPool;")
//            ));
//        }),
        new SimpleTypeAdapter(Type.getObjectType("net/minecraft/world/entity/Mob"), Type.getObjectType("net/minecraft/world/entity/monster/Monster"), (list, insn) -> {})
//        new SimpleTypeAdapter(Type.getObjectType("net/minecraft/world/item/enchantment/Enchantment"), Type.getObjectType("net/minecraft/world/item/enchantment/EnchantmentCategory"), (list, insn) ->
//            list.insert(insn, new FieldInsnNode(Opcodes.GETFIELD, "net/minecraft/world/item/enchantment/Enchantment", ASMAPI.mapField("f_44672_"), "Lnet/minecraft/world/item/enchantment/EnchantmentCategory;")))
    );

    private final BytecodeFixerUpper bfu;
    private final ConnectorUtil.CacheFile cacheFile;
    private final Path generatedJarPath;

    public BytecodeFixerUpperFrontend(ClassLookup cleanLookup, ClassLookup dirtyLookup, Path generatedJarPath) {
        this.bfu = new BytecodeFixerUpper(cleanLookup, dirtyLookup, FIELD_TYPE_ADAPTERS);

        this.generatedJarPath = generatedJarPath;
        this.cacheFile = ConnectorUtil.getCached(null, this.generatedJarPath);
        if (this.cacheFile.isUpToDate()) {
            this.bfu.getGenerator().loadExisting(this.generatedJarPath);
        }
    }

    public BytecodeFixerUpper unwrap() {
        return this.bfu;
    }

    public void saveGeneratedAdapterJar() throws IOException {
        Files.createDirectories(this.generatedJarPath.getParent());

        Files.deleteIfExists(this.generatedJarPath);
        Attributes attributes = new Attributes();
        attributes.putValue("FMLModType", "GAMELIBRARY");
        if (this.bfu.getGenerator().save(this.generatedJarPath, attributes)) {
            this.cacheFile.save();
        }
    }
}
