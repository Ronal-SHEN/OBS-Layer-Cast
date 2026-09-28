package starship.layercast.layer;

import starship.layercast.LayerCast;
import starship.layercast.platform.LoaderPlatform;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the mod that is drawing a GUI element, by walking the stack from the element up to the vanilla code that
 * called into the mod.
 * <p>
 * Frames are mapped to mods by the jar that contains their class; mixin handlers merged into vanilla classes carry
 * their mod id in the method name ({@code handler$abc000$modid$name}). When several mods are on the stack, a mod that
 * another one depends on is a library dispatching for it (MaLiLib calling MiniHUD, Architectury events, ...), so the
 * dependent mod owns the element; among independent mods the outermost one (the one called by vanilla) wins.
 * <p>
 * Only used for elements no vanilla component claims, and only while a mod layer is split out (or for occasional
 * discovery frames). Render thread only.
 */
public final class ModAttribution {
    /** Frames fetched per batch: the element API, the mod(s) and the vanilla caller usually fit in one batch. */
    private static final int ESTIMATED_DEPTH = 48;
    private static final StackWalker FAST = StackWalker.getInstance(
        Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.DROP_METHOD_INFO), ESTIMATED_DEPTH);
    private static final StackWalker NAMED = StackWalker.getInstance(Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE), ESTIMATED_DEPTH);
    private static final String UNRESOLVED = new String("unresolved");
    private static final int MAX_FRAMES = 64;
    private static final int MAX_MODS = 8;
    private static final Pattern HANDLER = Pattern.compile("^[A-Za-z]+\\$[a-z]{3}\\d{3}\\$([a-z0-9_\\-]+)\\$");
    private static final String NONE = "";
    private static final ClassValue<String> MOD_OF_CLASS = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            String mod = LoaderPlatform.Holder.get().modIdOf(type);
            return mod == null || isInfrastructure(mod) ? NONE : mod;
        }
    };
    private static final Map<String, Set<String>> DEPENDENCIES = new HashMap<>();
    /** Per calling class: the mod it always draws for, once known ({@code [0] == null} until then). */
    private static final ClassValue<String[]> CALL_SITES = new ClassValue<>() {
        @Override
        protected String[] computeValue(Class<?> type) {
            return new String[1];
        }
    };
    private static @Nullable Set<String> libraries;
    private static int lastCandidates;
    private static final String[] MODS = new String[MAX_MODS];

    private static long walks;
    private static long fullWalks;
    private static long namedWalks;
    private static long walkNanos;

    private ModAttribution() {
    }

    /** Mods that never draw a HUD of their own: the game, the loaders and their APIs, this mod. */
    static boolean isInfrastructure(String modId) {
        return switch (modId) {
            case "minecraft", "java", "fabricloader", "fabric-api", "fabric", "neoforge", "fml", "mixinextras", LayerCast.MOD_ID -> true;
            default -> modId.startsWith("fabric-") || modId.startsWith("layercast");
        };
    }

    /** The mod drawing the element that is being added right now, or {@code null} for vanilla code. */
    public static @Nullable String currentOwner() {
        long start = System.nanoTime();
        try {
            // Class-only walks are several times cheaper; method names are needed only to read the mod id of mixin
            // handlers merged into vanilla classes, i.e. when the stack shows no mod at all or only a library.
            String owner = FAST.walk(frames -> owner(frames.iterator(), false));
            if (owner == UNRESOLVED) {
                namedWalks++;
                owner = NAMED.walk(frames -> owner(frames.iterator(), true));
            }
            return owner;
        } finally {
            walkNanos += System.nanoTime() - start;
            walks++;
        }
    }

    private static @Nullable String owner(Iterator<StackWalker.StackFrame> frames, boolean methodNames) {
        // The first frame outside the element API is the call site. Classes of a mod that no other mod depends on,
        // once a full walk confirmed that mod as the owner, are answered from the cache without walking further
        // (a library owner never reaches the cache: it is resolved again with method names below).
        StackWalker.StackFrame caller = null;
        while (frames.hasNext()) {
            StackWalker.StackFrame frame = frames.next();
            String name = frame.getClassName();
            if (!drawingApi(name) && !skipped(name)) {
                caller = frame;
                break;
            }
        }
        if (caller == null) {
            return null;
        }
        String[] site = CALL_SITES.get(caller.getDeclaringClass());
        if (!methodNames && site[0] != null) {
            return site[0];
        }
        fullWalks++;
        String owner = fullWalk(caller, frames, methodNames);
        if (!methodNames) {
            if (lastCandidates == 0 || libraries().contains(owner)) {
                return UNRESOLVED;
            }
            // The call site's own mod won over everything else on the stack (only its libraries): the class
            // always draws for that mod, whichever library dispatched to it.
            if (owner.equals(MOD_OF_CLASS.get(caller.getDeclaringClass()))) {
                site[0] = owner;
            }
        }
        return owner;
    }

    private static @Nullable String fullWalk(StackWalker.StackFrame caller, Iterator<StackWalker.StackFrame> frames, boolean methodNames) {
        int count = 0;
        StackWalker.StackFrame next = caller;
        for (int depth = 0; depth < MAX_FRAMES && next != null; depth++, next = frames.hasNext() ? frames.next() : null) {
            StackWalker.StackFrame frame = next;
            Class<?> type = frame.getDeclaringClass();
            String name = type.getName();
            String mod;
            if (name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")) {
                mod = !methodNames || drawingApi(name) ? null : handlerMod(frame.getMethodName());
                if (mod == null) {
                    if (count > 0) {
                        break; // back in the vanilla code that called into the mod(s)
                    }
                    continue;
                }
            } else if (skipped(name)) {
                continue;
            } else {
                mod = MOD_OF_CLASS.get(type);
                if (mod.isEmpty()) {
                    continue;
                }
            }
            if (count < MAX_MODS && (count == 0 || !mod.equals(MODS[count - 1]))) {
                MODS[count++] = mod;
            }
        }
        lastCandidates = distinct(count);
        return choose(count);
    }

    private static int distinct(int count) {
        int distinct = 0;
        for (int i = 0; i < count; i++) {
            boolean seen = false;
            for (int j = 0; j < i && !seen; j++) {
                seen = MODS[j].equals(MODS[i]);
            }
            distinct += seen ? 0 : 1;
        }
        return distinct;
    }

    /** Mods that some loaded mod requires: their code may run on behalf of another mod. */
    private static Set<String> libraries() {
        if (libraries == null) {
            Set<String> set = new HashSet<>();
            LoaderPlatform platform = LoaderPlatform.Holder.get();
            for (String mod : platform.modIds()) {
                set.addAll(platform.requiredDependencies(mod));
            }
            libraries = set;
        }
        return libraries;
    }

    /** The element API itself; mixins into it decorate every element and say nothing about the owner. */
    private static boolean drawingApi(String className) {
        return className.startsWith("net.minecraft.client.gui.GuiGraphicsExtractor")
            || className.startsWith("net.minecraft.client.renderer.state.gui.")
            || className.equals("net.minecraft.client.gui.Font");
    }

    private static boolean skipped(String className) {
        return className.startsWith("java.") || className.startsWith("jdk.") || className.startsWith("sun.")
            || className.startsWith("starship.layercast.") || className.startsWith("org.spongepowered.")
            || className.startsWith("com.llamalad7.");
    }

    private static @Nullable String handlerMod(String methodName) {
        if (methodName.indexOf('$') < 0) {
            return null;
        }
        Matcher matcher = HANDLER.matcher(methodName);
        if (!matcher.find()) {
            return null;
        }
        String mod = matcher.group(1);
        return isInfrastructure(mod) ? null : mod;
    }

    /** {@code MODS[0..count)} from the innermost frame outwards. */
    private static @Nullable String choose(int count) {
        if (count == 0) {
            return null;
        }
        if (count == 1) {
            return MODS[0];
        }
        for (int i = count - 1; i >= 0; i--) {
            String candidate = MODS[i];
            boolean library = false;
            for (int j = 0; j < count && !library; j++) {
                library = !MODS[j].equals(candidate) && dependencies(MODS[j]).contains(candidate);
            }
            if (!library) {
                return candidate;
            }
        }
        return MODS[count - 1];
    }

    /** Required dependencies of a mod, transitively. */
    private static Set<String> dependencies(String modId) {
        Set<String> cached = DEPENDENCIES.get(modId);
        if (cached != null) {
            return cached;
        }
        Set<String> all = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(LoaderPlatform.Holder.get().requiredDependencies(modId));
        while (!queue.isEmpty()) {
            String dependency = queue.poll();
            if (all.add(dependency)) {
                queue.addAll(LoaderPlatform.Holder.get().requiredDependencies(dependency));
            }
        }
        DEPENDENCIES.put(modId, all);
        return all;
    }

    /** Attributions since the last call: count, how many needed a full walk, average cost (diagnostics). */
    public static String takeStats() {
        String stats = String.format("%d attributions (%d full walks, %d with method names), %.2f us each", walks,
            fullWalks, namedWalks, walks == 0 ? 0 : walkNanos / 1000.0 / walks);
        walks = 0;
        fullWalks = 0;
        namedWalks = 0;
        walkNanos = 0;
        return stats;
    }
}
