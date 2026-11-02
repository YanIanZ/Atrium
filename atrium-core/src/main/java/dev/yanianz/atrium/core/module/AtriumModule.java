package dev.yanianz.atrium.core.module;

import java.util.Set;

/**
 * A feature module (homes, economy, chat, ...). Implementations are discovered with
 * {@link java.util.ServiceLoader}: list the class in
 * {@code META-INF/services/dev.yanianz.atrium.core.module.AtriumModule} of the module project.
 *
 * <p>Everything registered through the {@link ModuleContext} (listeners, tasks, services, permissions,
 * user components) is undone automatically when the module is disabled or fails to enable.
 */
public interface AtriumModule {
    /** Stable lower-case id, also the config file name ({@code modules/<id>.yml}) and message bundle. */
    String id();

    default String description() {
        return "";
    }

    /** Modules that must be enabled first. If one is disabled or failed, this module is skipped. */
    default Set<String> dependencies() {
        return Set.of();
    }

    /** Modules that are enabled first when present, but are not required. */
    default Set<String> softDependencies() {
        return Set.of();
    }

    default boolean enabledByDefault() {
        return true;
    }

    void enable(ModuleContext context) throws Exception;

    default void disable() {}

    /** Called by {@code /atrium reload} after configs and messages were reloaded. */
    default void reload() throws Exception {}
}
