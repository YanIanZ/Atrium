package dev.yanianz.atrium.core.module;

import dev.yanianz.atrium.api.service.ServiceRegistry;
import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.permission.Permissions;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import dev.yanianz.atrium.core.user.UserManager;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.bukkit.event.Listener;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

/** Everything a module may use. Registrations made here are reverted when the module is disabled. */
public interface ModuleContext {
    String moduleId();

    JavaPlugin plugin();

    Logger logger();

    Scheduler scheduler();

    Messages messages();

    Cmds commands();

    Database database();

    UserManager users();

    ServiceRegistry services();

    CoreConfig coreConfig();

    /** {@code plugins/Atrium/data/<module>/}, created on first call. */
    Path dataDirectory();

    /** Typed config at {@code plugins/Atrium/modules/<module>.yml}. */
    <T> ConfigHandle<T> config(Class<T> type, Supplier<T> defaults) throws Exception;

    /** Applies this module's schema migrations (namespace = module id). Blocking; call from enable. */
    void migrate(List<Migration> migrations) throws Exception;

    void command(CommandSpec spec);

    void listen(Listener listener);

    /** Cancels the task when the module is disabled. Thread-safe; finished tasks are dropped. Returns the same handle. */
    TaskHandle track(TaskHandle task);

    <T> void service(Class<T> type, T implementation);

    <T> void component(UserComponentType<T> type);

    void permission(String node, PermissionDefault def, String description);

    /** Shared persisted cooldowns; keys {@code "<module>:<thing>"}. Prefer {@code tryAcquire}. */
    dev.yanianz.atrium.core.user.Cooldowns cooldowns();

    /** Permission registry scoped to this module (also see {@link Permissions#limit}). */
    Permissions permissions();

    /**
     * Dupe-safe chest menus (see {@link dev.yanianz.atrium.core.menu.MenuService}). Menus opened through the
     * module's context close automatically when the module is disabled. The default implementation (for
     * custom/test contexts) returns the plugin-wide service.
     */
    default dev.yanianz.atrium.core.menu.MenuService menus() {
        return dev.yanianz.atrium.core.menu.MenuManager.installed();
    }

    /**
     * Registers how a user component converts to bytes, enabling export/import by component id
     * ({@link dev.yanianz.atrium.core.user.UserManager#exportComponent}). Undone on disable. The default
     * implementation (custom/test contexts) registers without automatic undo.
     */
    default <T> void codec(UserComponentType<T> type, dev.yanianz.atrium.core.user.ComponentCodec<T> codec) {
        users().registerCodec(type, codec);
    }

    /**
     * Publishes data that may follow the player's active profile (SourbyCraft MultiProfile) in the core
     * {@link dev.yanianz.atrium.api.profile.ProfileScopedDataRegistry}. Undone on disable. Whether it really
     * follows the profile is decided by the server owner (config of the {@code sourbycraft} module).
     */
    default void profileScoped(dev.yanianz.atrium.api.profile.ProfileScopedData data) {
        services().get(dev.yanianz.atrium.api.profile.ProfileScopedDataRegistry.class)
                .ifPresent(registry -> registry.register(data));
    }
}
