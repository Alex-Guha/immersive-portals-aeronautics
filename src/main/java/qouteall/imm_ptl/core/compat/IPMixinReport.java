/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Tells whether our compat hooks in other mods are alive. Most of them are {@code require = 0}: when a mod update moves
 * what one looks for, Mixin says nothing and the feature is gone. {@link IPOptionalModMixinPlugin} hands over every
 * applied mixin, and each injector is checked for a call from its target class: Mixin merges a handler in any case, but
 * only an injection that matched calls it. A handler with {@code expect = 0} may match nothing on purpose and is not
 * counted.
 * <p>
 * The check waits for {@link #report}: MixinExtras applies its wrappers after {@code postApply}. Only loaded classes are
 * known, so a target that never loaded counts for nothing; {@code scripts/mixincheck.py} checks the names statically.
 */
public final class IPMixinReport {
    private static final Logger LOGGER = LoggerFactory.getLogger("IPCompat");

    private static final Set<String> INJECTORS = Set.of(
        "Lorg/spongepowered/asm/mixin/injection/Inject;",
        "Lorg/spongepowered/asm/mixin/injection/Redirect;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
        "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
        "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
        "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
        "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
        "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
        "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;",
        "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"
    );

    /** The mods hooked, by package of the target class. */
    private static final Map<String, String> MOD_BY_TARGET = Map.of(
        "com.simibubi.create.", "Create",
        "dev.engine_room.flywheel.", "Flywheel",
        "dev.ryanhcode.sable.", "Sable",
        "dev.simulated_team.simulated.", "Aeronautics",
        "dev.eriksonn.aeronautics.", "Aeronautics",
        "com.seibel.distanthorizons.", "Distant Horizons",
        "me.cortex.voxy.", "Voxy",
        "net.irisshaders.", "Iris",
        "net.caffeinemc.mods.sodium.", "Sodium",
        "top.leonx.dynlight.", "Create Dynamic Lights"
    );

    private record Applied(String mod, String mixin, ClassNode target, List<String> handlers) {
    }

    private static final List<Applied> PENDING = new ArrayList<>();
    /** Per handler (mixin#method) with its mod: whether any target calls it. */
    private static final Map<String, Boolean> MATCHED = new HashMap<>();
    private static final Map<String, String> MOD_OF_HANDLER = new HashMap<>();
    private static final Set<String> WARNED = new HashSet<>();

    private IPMixinReport() {
    }

    /** Whether {@code annotationDesc} is a Mixin or MixinExtras injector annotation (one that names its target methods). */
    public static boolean isInjector(String annotationDesc) {
        return INJECTORS.contains(annotationDesc);
    }

    static synchronized void applied(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {
        try {
            List<String> handlers = handlers(info.getClassNode(ClassReader.SKIP_CODE));
            if (!handlers.isEmpty()) {
                PENDING.add(new Applied(modOf(targetClassName), mixinClassName, targetClass, handlers));
            }
        } catch (RuntimeException e) {
            LOGGER.debug("[IPCompat] could not read {}", mixinClassName, e);
        }
    }

    private static String modOf(String targetClassName) {
        for (Map.Entry<String, String> entry : MOD_BY_TARGET.entrySet()) {
            if (targetClassName.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "others";
    }

    private static List<String> handlers(ClassNode mixin) {
        List<String> handlers = new ArrayList<>();
        for (MethodNode method : mixin.methods) {
            if (isExpectedInjector(method.visibleAnnotations) || isExpectedInjector(method.invisibleAnnotations)) {
                handlers.add(method.name);
            }
        }
        return handlers;
    }

    private static boolean isExpectedInjector(List<AnnotationNode> annotations) {
        if (annotations == null) {
            return false;
        }
        for (AnnotationNode annotation : annotations) {
            if (INJECTORS.contains(annotation.desc)) {
                return !isOptional(annotation);
            }
        }
        return false;
    }

    private static boolean isOptional(AnnotationNode annotation) {
        if (annotation.values != null) {
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if ("expect".equals(annotation.values.get(i)) && Integer.valueOf(0).equals(annotation.values.get(i + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Checks what was applied since the last call, warns about hooks that match nothing, logs a line per mod. */
    public static synchronized void report() {
        evaluate();
        summary().forEach(LOGGER::info);
    }

    /** @return a line per mod: how many hooks are alive, then each hook that matched nothing */
    public static synchronized List<String> summary() {
        evaluate();
        Map<String, int[]> counts = new TreeMap<>();
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, Boolean> handler : MATCHED.entrySet()) {
            int[] count = counts.computeIfAbsent(MOD_OF_HANDLER.get(handler.getKey()), mod -> new int[2]);
            count[0]++;
            if (handler.getValue()) {
                count[1]++;
            } else {
                dead.add(handler.getKey());
            }
        }
        List<String> lines = new ArrayList<>();
        counts.forEach((mod, count) -> lines.add("[IPCompat] %s: %d/%d hooks matched".formatted(mod, count[1], count[0])));
        dead.stream().sorted().forEach(handler -> lines.add("[IPCompat] matched nothing: " + shortName(handler)));
        return lines;
    }

    private static String shortName(String handler) {
        return handler.substring(handler.lastIndexOf('.') + 1);
    }

    private static void evaluate() {
        Map<ClassNode, Set<String>> called = new LinkedHashMap<>();
        for (Applied applied : PENDING) {
            Set<String> names = called.computeIfAbsent(applied.target(), IPMixinReport::calledMethods);
            for (String handler : applied.handlers()) {
                String key = applied.mixin() + "#" + handler;
                String suffix = "$" + handler;
                boolean matched = names.stream().anyMatch(name -> name.endsWith(suffix));
                MATCHED.merge(key, matched, Boolean::logicalOr);
                MOD_OF_HANDLER.put(key, applied.mod());
            }
        }
        PENDING.clear();
        for (Map.Entry<String, Boolean> handler : MATCHED.entrySet()) {
            if (!handler.getValue() && WARNED.add(handler.getKey())) {
                LOGGER.warn("[IPCompat] {} hook {} matched nothing: {} changed, or the hook is stale",
                    MOD_OF_HANDLER.get(handler.getKey()), shortName(handler.getKey()), MOD_OF_HANDLER.get(handler.getKey()));
            }
        }
    }

    private static Set<String> calledMethods(ClassNode target) {
        Set<String> names = new HashSet<>();
        for (MethodNode method : target.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call) {
                    names.add(call.name);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    for (Object argument : dynamic.bsmArgs) {
                        if (argument instanceof Handle handle) {
                            names.add(handle.getName());
                        }
                    }
                }
            }
        }
        return names;
    }
}
