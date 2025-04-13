package org.sinytra.adapter.game.transform;

import net.minecraftforge.fart.api.ClassProvider;
import net.minecraftforge.fart.internal.ClassProviderImpl;
import net.minecraftforge.fart.internal.EnhancedClassRemapper;
import net.minecraftforge.fart.internal.EnhancedRemapper;
import net.minecraftforge.fart.internal.RenamingTransformer;
import net.minecraftforge.srgutils.IMappingFile;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;
import org.sinytra.adapter.game.jar.IntermediateMapping;
import org.sinytra.adapter.patch.analysis.MethodCallAnalyzer;
import org.sinytra.adapter.patch.analysis.selector.AnnotationHandle;
import org.sinytra.adapter.patch.analysis.selector.AnnotationValueHandle;
import org.sinytra.adapter.patch.util.MethodQualifier;
import org.spongepowered.asm.mixin.gen.AccessorInfo;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_STATIC;

public final class OptimizedRenamingTransformer extends RenamingTransformer {
    private static final String CLASS_DESC_PATTERN = "^L[a-zA-Z0-9/$_]+;$";
    private static final String FQN_CLASS_NAME_PATTERN = "^(?:[a-zA-Z0-9$_]+\\.)*[a-zA-Z0-9$_]+$";
    private static final String INTERNAL_CLASS_NAME_PATTERN = "^(?:[a-zA-Z0-9$_]+/)*[a-zA-Z0-9$_]+$";
    private static final Pattern FIELD_QUALIFIER_PATTERN = Pattern.compile("^(?<owner>L[\\\\\\w/$]+;)?(?<name>\\w+)(?::(?<desc>\\[*[ZCBSIFJD]|\\[*L[a-zA-Z0-9/_$]+;))?$");
    private static final String ACCESSOR_METHOD_PATTERN = "^.*(Method_|Field_|Comp_).*$";
    private static final Pattern REGEX_DESC_PATTERN = Pattern.compile("^.*(net\\\\/minecraft\\\\/class_\\d{4}).*$");

    private final boolean remapRefs;

    public OptimizedRenamingTransformer(EnhancedRemapper remapper, boolean collectAbstractParams, boolean remapRefs) {
        super(remapper, collectAbstractParams);

        this.remapRefs = remapRefs;
    }

    @Override
    protected void postProcess(ClassNode node) {
        super.postProcess(node);

        // Remap raw values (usually found in reflection calls) and unmapped mixin annotations
        // This is done in a "post-processing" phase rather than inside the main remapper's mapValue method
        // so that we're able to determine the "remap" mixin annotation value ahead of time, and only remap it when necessary
        PostProcessRemapper postProcessRemapper = new PostProcessRemapper(((MixinAwareEnhancedRemapper) this.remapper).flatMappings, this.remapper);
        if (node.visibleAnnotations != null) {
            for (AnnotationNode annotation : node.visibleAnnotations) {
                postProcessRemapper.mapAnnotationValues(annotation.values);
            }
        }
        if (node.invisibleAnnotations != null) {
            for (AnnotationNode annotation : node.invisibleAnnotations) {
                postProcessRemapper.mapAnnotationValues(annotation.values);
            }
        }
        for (MethodNode method : node.methods) {
            if (method.visibleAnnotations != null) {
                for (AnnotationNode annotation : method.visibleAnnotations) {
                    processMixinAnnotation(annotation, postProcessRemapper);
                }
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof LdcInsnNode ldc) {
                    ldc.cst = postProcessRemapper.mapValue(ldc.cst);
                }
                if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (int i = 0; i < indy.bsmArgs.length; i++) {
                        indy.bsmArgs[i] = postProcessRemapper.mapValue(indy.bsmArgs[i]);
                        indy.bsm = (Handle) postProcessRemapper.mapValue(indy.bsm);
                    }
                }
            }
            avoidAmbigousMappingRecursion(node, method);
        }
        for (FieldNode field : node.fields) {
            field.value = postProcessRemapper.mapValue(field.value);
        }
    }

    private void avoidAmbigousMappingRecursion(ClassNode classNode, MethodNode method) {
        int parentMethods = countAmbigousOverridenMethods(classNode, method);
        if (parentMethods > 1) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode minsn && minsn.getOpcode() == Opcodes.INVOKEVIRTUAL && minsn.owner.equals(classNode.name) && minsn.name.equals(method.name) && minsn.desc.equals(method.desc)) {
                    List<AbstractInsnNode> insns = MethodCallAnalyzer.findMethodCallParamInsns(method, minsn);
                    if (!insns.isEmpty() && insns.getFirst() instanceof VarInsnNode varInsn && varInsn.var == 0) {
                        method.instructions.set(minsn, new MethodInsnNode(Opcodes.INVOKESPECIAL, classNode.superName, minsn.name, minsn.desc, minsn.itf));   
                    }
                }
            }
        }
        // Look for ambigous methods in our own class
        if (parentMethods > 0) {
            int i = 1;
            for (MethodNode m : classNode.methods) {
                if (m != method && m.name.equals(method.name) && m.desc.equals(method.desc)) {
                    m.name += "$connector_disabled$" + i;
                }
            }
        }
    }

    private int countAmbigousOverridenMethods(ClassNode classNode, MethodNode method) {
        return classNode.superName != null ? this.remapper.getClass(classNode.name)
            .map(c -> (int) c.getMethods().stream()
                .flatMap(Optional::stream)
                .filter(m -> !m.getName().equals(m.getMapped()) && m.getMapped().equals(method.name) && method.desc.equals(this.remapper.mapMethodDesc(m.getDescriptor())) 
                    && (m.getAccess() & (ACC_PRIVATE | ACC_STATIC)) == 0)
                .count())
            .orElse(0) : 0;
    }

    private void processMixinAnnotation(AnnotationNode annotation, PostProcessRemapper postProcessRemapper) {
        AnnotationHandle handle = new AnnotationHandle(annotation);
        // Take care of regex method specifiers
        handle.<List<String>>getValue("method")
            .map(AnnotationValueHandle::get)
            .filter(list -> list.size() == 1 && list.getFirst().startsWith("desc="))
            .ifPresent(h -> {
                String method = h.getFirst();
                Matcher matcher = REGEX_DESC_PATTERN.matcher(method);
                if (matcher.matches()) {
                    String className = matcher.group(1);
                    String mapped = ((MixinAwareEnhancedRemapper) this.remapper).flatMappings.map(className.replace("\\/", "/"));
                    if (mapped != null) {
                        String mappedClassName = mapped.replace("/", "\\/");
                        String mappedMethod = method.replace(className, mappedClassName);
                        h.set(0, mappedMethod);
                    }
                }
            });
        // If remap has been set to false during compilation, we must manually map the annotation values ourselves instead of relying on the provided refmap
        if (this.remapRefs || handle.<Boolean>getValue("remap").map(h -> !h.get()).orElse(false)) {
            postProcessRemapper.mapAnnotationValues(annotation.values);
        }
    }

    private record PostProcessRemapper(IntermediateMapping flatMappings, Remapper remapper) {
        public void mapAnnotationValues(List values) {
            if (values != null) {
                for (int i = 1; i < values.size(); i += 2) {
                    values.set(i, mapAnnotationValue(values.get(i)));
                }
            }
        }

        public Object mapAnnotationValue(Object obj) {
            if (obj instanceof AnnotationNode annotation) {
                mapAnnotationValues(annotation.values);
            }
            else if (obj instanceof List list) {
                list.replaceAll(this::mapAnnotationValue);
            }
            else {
                return mapValue(obj);
            }
            return obj;
        }

        public Object mapValue(Object value) {
            if (value instanceof String str) {
                if (str.matches(CLASS_DESC_PATTERN)) {
                    String mapped = this.flatMappings.map(str.substring(1, str.length() - 1));
                    if (mapped != null) {
                        return 'L' + mapped + ';';
                    }
                }
                else if (str.matches(FQN_CLASS_NAME_PATTERN)) {
                    String mapped = this.flatMappings.map(str.replace('.', '/'));
                    if (mapped != null) {
                        return mapped.replace('/', '.');
                    }
                }
                else if (str.matches(INTERNAL_CLASS_NAME_PATTERN)) {
                    String mapped = this.flatMappings.map(str);
                    if (mapped != null) {
                        return mapped;
                    }
                }

                MethodQualifier qualifier = MethodQualifier.create(str).orElse(null);
                if (qualifier != null && qualifier.desc() != null) {
                    String owner = qualifier.owner() != null ? this.remapper.mapDesc(qualifier.owner()) : "";
                    String name = qualifier.name() != null ? this.flatMappings.mapMethodOrDefault(qualifier.name(), qualifier.desc()) : "";
                    String desc = this.remapper.mapMethodDesc(qualifier.desc());
                    return owner + name + desc;
                }
                else {
                    Matcher fieldMatcher = FIELD_QUALIFIER_PATTERN.matcher(str);
                    if (fieldMatcher.matches()) {
                        String owner = fieldMatcher.group("owner");
                        String name = fieldMatcher.group("name");
                        String desc = fieldMatcher.group("desc");

                        if (owner != null || name != null && (name.startsWith("field_") || name.startsWith("comp_"))) {
                            String mappedOwner = owner != null ? this.remapper.mapDesc(owner) : "";
                            String mappedName = name != null ? this.flatMappings.mapField(name, desc != null ? desc : "") : "";

                            return mappedOwner + mappedName + (desc != null ? ":" + this.remapper.mapDesc(desc) : "");
                        }
                    }
                }

                String mapped = this.flatMappings.map(str);
                if (mapped != null) {
                    return mapped;
                }
            }
            return this.remapper.mapValue(value);
        }
    }

    public static final class IntermediaryClassProvider implements ClassProvider {
        private final ClassProvider upstream;
        private final IMappingFile forwardMapping;
        private final EnhancedRemapper remapper;

        private final Map<String, Optional<IClassInfo>> classCache = new ConcurrentHashMap<>();

        public IntermediaryClassProvider(ClassProvider upstream, IMappingFile forwardMapping, IMappingFile reverseMapping, Consumer<String> log) {
            this.upstream = upstream;
            this.forwardMapping = forwardMapping;
            this.remapper = new EnhancedRemapper(upstream, reverseMapping, log);
        }

        @Override
        public Optional<? extends IClassInfo> getClass(String s) {
            return this.classCache.computeIfAbsent(s, this::computeClassInfo)
                .or(() -> this.upstream.getClass(s));
        }

        @Override
        public Optional<byte[]> getClassBytes(String cls) {
            return this.upstream.getClassBytes(this.forwardMapping.remapClass(cls));
        }

        private Optional<IClassInfo> computeClassInfo(String cls) {
            return getClassBytes(cls).map(data -> {
                ClassReader reader = new ClassReader(data);
                ClassWriter writer = new ClassWriter(0);
                ClassRemapper remapper = new EnhancedClassRemapper(writer, this.remapper, null);
                MixinTargetAnalyzer analyzer = new MixinTargetAnalyzer(Opcodes.ASM9, remapper);
                reader.accept(analyzer, ClassReader.SKIP_CODE);
                analyzer.targets.remove(cls);

                byte[] remapped = writer.toByteArray();
                IClassInfo info = new ClassProviderImpl.ClassInfo(remapped);
                return !analyzer.targets.isEmpty() ? new MixinClassInfo(info, analyzer.targets) : info;
            });
        }

        @Override
        public void close() throws IOException {
            this.upstream.close();
        }
    }

    public static class MixinAwareEnhancedRemapper extends EnhancedRemapper {
        private final IntermediateMapping flatMappings;

        public MixinAwareEnhancedRemapper(ClassProvider classProvider, IMappingFile map, IntermediateMapping flatMappings, Consumer<String> log) {
            super(classProvider, map, log);
            this.flatMappings = flatMappings;
        }

        @Override
        public String map(final String key) {
            String fastMapped = this.flatMappings.map(key);
            if (fastMapped != null) {
                return fastMapped;
            }
            return super.map(key);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String fastMapped = this.flatMappings.mapField(name, descriptor);
            if (fastMapped != null) {
                return fastMapped;
            }
            return this.classProvider.getClass(owner)
                .map(cls -> {
                    if (cls instanceof MixinClassInfo mcls) {
                        for (String parent : mcls.computedParents()) {
                            String mapped = super.mapFieldName(parent, name, descriptor);
                            if (!name.equals(mapped)) {
                                return mapped;
                            }
                        }
                    }
                    return null;
                })
                .orElseGet(() -> super.mapFieldName(owner, name, descriptor));
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            String fastMapped = this.flatMappings.mapMethod(name, descriptor);
            if (fastMapped != null) {
                return fastMapped;
            }
            return this.classProvider.getClass(owner)
                .map(cls -> {
                    // Handle methods belonging to interfaces added through @Implements
                    if (cls instanceof MixinClassInfo && !name.startsWith("lambda$")) {
                        int interfacePrefix = name.indexOf("$");
                        if (interfacePrefix > -1 && name.lastIndexOf("$") == interfacePrefix) {
                            String actualName = name.substring(interfacePrefix + 1);
                            String fastMappedLambda = this.flatMappings.mapMethod(actualName, descriptor);
                            String mapped = fastMappedLambda != null ? fastMappedLambda : mapMethodName(owner, actualName, descriptor);
                            return name.substring(0, interfacePrefix + 1) + mapped;
                        }
                        if (name.matches(ACCESSOR_METHOD_PATTERN)) {
                            AccessorInfo.AccessorName accessorName = AccessorInfo.AccessorName.of(name);
                            if (accessorName != null) {
                                String mapped = this.flatMappings.mapMethod(accessorName.name, descriptor);
                                if (mapped != null) {
                                    return accessorName.prefix + mapped.substring(0, 1).toUpperCase() + mapped.substring(1);
                                }
                            }
                        }
                    }
                    return null;
                })
                .orElseGet(() -> super.mapMethodName(owner, name, descriptor));
        }

        @Override
        public String mapPackageName(String name) {
            // We don't need to map these
            return name;
        }
    }

    private static class MixinTargetAnalyzer extends ClassVisitor {
        private final Set<String> targets = new HashSet<>();

        public MixinTargetAnalyzer(int api, ClassVisitor classVisitor) {
            super(api, classVisitor);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return new MixinAnnotationVisitor(this.api, super.visitAnnotation(descriptor, visible), this.targets, null);
        }
    }

    private static class MixinAnnotationVisitor extends AnnotationVisitor {
        private final Set<String> targets;
        private final String attributeName;

        public MixinAnnotationVisitor(int api, AnnotationVisitor annotationVisitor, Set<String> targets, String attributeName) {
            super(api, annotationVisitor);

            this.targets = targets;
            this.attributeName = attributeName;
        }

        @Override
        public void visit(String name, Object value) {
            super.visit(name, value);
            if ("value".equals(this.attributeName) && value instanceof Type type) {
                this.targets.add(type.getInternalName());
            }
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            return new MixinAnnotationVisitor(this.api, super.visitArray(name), this.targets, name);
        }
    }

    private record MixinClassInfo(ClassProvider.IClassInfo wrapped, Set<String> computedParents) implements ClassProvider.IClassInfo {
        // Hacky way to "inject" members from the computed parent while preserving the real ones
        @Override
        public Collection<String> getInterfaces() {
            return Stream.concat(this.wrapped.getInterfaces().stream(), this.computedParents.stream()).toList();
        }

        //@formatter:off
        @Override public int getAccess() {return this.wrapped.getAccess();}
        @Override public String getName() {return this.wrapped.getName();}
        @Override public @Nullable String getSuper() {return this.wrapped.getSuper();}
        @Override public Collection<? extends ClassProvider.IFieldInfo> getFields() {return this.wrapped.getFields();}
        @Override public Optional<? extends ClassProvider.IFieldInfo> getField(String name) {return this.wrapped.getField(name);}
        @Override public Collection<? extends ClassProvider.IMethodInfo> getMethods() {return this.wrapped.getMethods();}
        @Override public Optional<? extends ClassProvider.IMethodInfo> getMethod(String name, String desc) {return this.wrapped.getMethod(name, desc);}
        //@formatter:on
    }
}
