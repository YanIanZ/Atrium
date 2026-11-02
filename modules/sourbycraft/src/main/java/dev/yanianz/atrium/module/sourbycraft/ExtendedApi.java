package dev.yanianz.atrium.module.sourbycraft;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Optional members of newer, locally built SourbyCraft APIs that the official 26.2 API (Atrium's compile
 * baseline) does not have. Each is detected independently at startup by API presence (class lookup, record
 * component names, public method signatures; nothing of the server's internals) and used through method
 * handles, so Atrium needs no extra compile dependency and runs unchanged on the official API.
 *
 * <ul>
 *   <li>{@code ProfileInfo.name()}, {@code members()}, {@code virtualId()}: profile name, the player's role and
 *       the profile's virtual id for {@code %atrium_profile%}, {@code %atrium_profile_role%}, {@code /atrium profile}.</li>
 *   <li>{@code ProfileUid.member(UUID, UUID)}: per-member virtual id.</li>
 *   <li>{@code ProfilePurse.backend()} (a registered service): purse backend for the economy check.</li>
 *   <li>{@code AuroraProfiles.awaitProfileReady(UUID)}: the join restore finished.</li>
 *   <li>{@code ProfileStateAdapter.required()} / {@code applyMissing(Player)}: the adapter implements both as
 *       plain methods; this only reports whether the server will call them.</li>
 * </ul>
 */
final class ExtendedApi {
    static final String PACKAGE = "dev.iyanz.sourbycraft.api.profile.";

    private final @Nullable MethodHandle name;
    private final @Nullable MethodHandle members;
    private final @Nullable MethodHandle virtualId;
    private final @Nullable MethodHandle memberUid;
    private final @Nullable Class<?> purseType;
    private final @Nullable MethodHandle purseBackend;
    private final @Nullable MethodHandle awaitReady;
    private final boolean adapterHooks;

    private ExtendedApi(@Nullable MethodHandle name, @Nullable MethodHandle members, @Nullable MethodHandle virtualId,
            @Nullable MethodHandle memberUid, @Nullable Class<?> purseType, @Nullable MethodHandle purseBackend,
            @Nullable MethodHandle awaitReady, boolean adapterHooks) {
        this.name = name;
        this.members = members;
        this.virtualId = virtualId;
        this.memberUid = memberUid;
        this.purseType = purseType;
        this.purseBackend = purseBackend;
        this.awaitReady = awaitReady;
        this.adapterHooks = adapterHooks;
    }

    /** Nothing extended: the official API. */
    static final ExtendedApi NONE = new ExtendedApi(null, null, null, null, null, null, null, false);

    /**
     * Detects the extended members.
     *
     * @param profileInfo    the {@code ProfileInfo} record class
     * @param auroraProfiles the {@code AuroraProfiles} interface
     * @param stateAdapter   the {@code ProfileStateAdapter} interface
     * @param loader         loads the optional classes ({@code ProfileUid}, {@code ProfilePurse}) by name
     */
    static ExtendedApi detect(Class<?> profileInfo, Class<?> auroraProfiles, Class<?> stateAdapter,
            Function<String, @Nullable Class<?>> loader) {
        MethodHandle name = component(profileInfo, "name", String.class);
        MethodHandle members = component(profileInfo, "members", Map.class);
        MethodHandle virtualId = component(profileInfo, "virtualId", UUID.class);
        MethodHandle memberUid = null;
        Class<?> uid = loader.apply(PACKAGE + "ProfileUid");
        if (uid != null) {
            memberUid = staticMethod(uid, "member", MethodType.methodType(UUID.class, UUID.class, UUID.class));
        }
        Class<?> purse = loader.apply(PACKAGE + "ProfilePurse");
        MethodHandle backend = purse == null ? null : instanceMethod(purse, "backend", String.class);
        MethodHandle await = instanceMethod(auroraProfiles, "awaitProfileReady", CompletableFuture.class, UUID.class);
        boolean hooks = hasMethod(stateAdapter, "required", boolean.class)
                && hasMethod(stateAdapter, "applyMissing", void.class, loader.apply("org.bukkit.entity.Player"));
        return new ExtendedApi(name, members, virtualId, memberUid, backend == null ? null : purse, backend, await, hooks);
    }

    /** Class lookup without initialisation; null when absent. */
    static Function<String, @Nullable Class<?>> classLoader(ClassLoader loader) {
        return className -> {
            try {
                return Class.forName(className, false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                return null;
            }
        };
    }

    /** Accessor of a record component with exactly this name and type, or null. */
    static @Nullable MethodHandle component(Class<?> record, String name, Class<?> type) {
        RecordComponent[] components = record.getRecordComponents();
        if (components == null) {
            return null;
        }
        for (RecordComponent component : components) {
            if (component.getName().equals(name) && component.getType() == type) {
                try {
                    return MethodHandles.publicLookup().unreflect(component.getAccessor());
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static @Nullable MethodHandle staticMethod(Class<?> owner, String name, MethodType type) {
        try {
            return MethodHandles.publicLookup().findStatic(owner, name, type);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
        }
    }

    private static @Nullable MethodHandle instanceMethod(Class<?> owner, String name, Class<?> returns, Class<?>... params) {
        try {
            Method method = owner.getMethod(name, params);
            if (Modifier.isStatic(method.getModifiers()) || !returns.isAssignableFrom(method.getReturnType())) {
                return null;
            }
            return MethodHandles.publicLookup().unreflect(method);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
        }
    }

    private static boolean hasMethod(Class<?> owner, String name, Class<?> returns, @Nullable Class<?>... params) {
        for (Class<?> param : params) {
            if (param == null) {
                return false;
            }
        }
        try {
            return owner.getMethod(name, params).getReturnType() == returns;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // ---- queries; every one falls back to null/false when the member is absent or fails ----

    @Nullable String name(Object info) {
        Object value = invoke(name, info);
        return value instanceof String s ? s : null;
    }

    /** The player's role in the profile; null when the API has no member list. */
    @Nullable String role(Object info, UUID player) {
        Object value = invoke(members, info);
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Object role = map.get(player);
        return role == null ? "NONE" : role.toString();
    }

    @Nullable UUID virtualId(Object info) {
        Object value = invoke(virtualId, info);
        return value instanceof UUID id ? id : null;
    }

    @Nullable UUID memberVirtualId(UUID profile, UUID member) {
        if (memberUid == null) {
            return null;
        }
        try {
            return (UUID) memberUid.invoke(profile, member);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Whether the API declares a purse service type (ProfilePurse). */
    boolean purseApi() {
        return purseType != null;
    }

    /** Backend of the registered purse, or null; {@code services} loads a registered service by type. */
    @Nullable String purseBackend(Function<Class<?>, @Nullable Object> services) {
        if (purseType == null || purseBackend == null) {
            return null;
        }
        Object purse = services.apply(purseType);
        Object value = purse == null ? null : invoke(purseBackend, purse);
        return value instanceof String s ? s : null;
    }

    /** {@code awaitProfileReady} of the extended API, or null on the official API (or when it throws). */
    @Nullable CompletableFuture<?> awaitReady(Object profiles, UUID player) {
        if (awaitReady == null) {
            return null;
        }
        try {
            return (CompletableFuture<?>) awaitReady.invoke(profiles, player);
        } catch (Throwable e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Whether the server's adapter interface has {@code required()} and {@code applyMissing(Player)}. */
    boolean adapterHooks() {
        return adapterHooks;
    }

    private static @Nullable Object invoke(@Nullable MethodHandle handle, Object target) {
        if (handle == null) {
            return null;
        }
        try {
            return handle.invoke(target);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Which extras were found, for the startup log; empty = the official API. */
    List<String> features() {
        List<String> found = new ArrayList<>();
        if (name != null) {
            found.add("profile-name");
        }
        if (members != null) {
            found.add("members");
        }
        if (virtualId != null) {
            found.add("virtual-id");
        }
        if (memberUid != null) {
            found.add("member-uid");
        }
        if (purseType != null && purseBackend != null) {
            found.add("purse");
        }
        if (awaitReady != null) {
            found.add("await-ready");
        }
        if (adapterHooks) {
            found.add("adapter-required/applyMissing");
        }
        return found;
    }
}
