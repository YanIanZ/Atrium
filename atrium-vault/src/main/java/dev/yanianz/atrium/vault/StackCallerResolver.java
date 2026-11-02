package dev.yanianz.atrium.vault;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Finds the plugin that called into Vault by walking at most {@value #MAX_FRAMES} stack frames and mapping the first
 * class that was not loaded by Vault's own class loader to its providing plugin. Class to plugin lookups are cached in a
 * {@link ClassValue}, so a call costs one short, lazy stack walk.
 */
public final class StackCallerResolver implements CallerResolver {
    static final int MAX_FRAMES = 24;
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final ClassLoader own;
    private final ClassValue<String> pluginOf = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            try {
                return JavaPlugin.getProvidingPlugin(type).getName();
            } catch (RuntimeException notAPluginClass) {
                return "";
            }
        }
    };

    public StackCallerResolver(ClassLoader own) {
        this.own = own;
    }

    @Override
    public String caller() {
        try {
            return WALKER.walk(frames -> frames.limit(MAX_FRAMES)
                    .map(StackWalker.StackFrame::getDeclaringClass)
                    .filter(type -> type.getClassLoader() != own)
                    .map(pluginOf::get)
                    .filter(name -> !name.isEmpty())
                    .findFirst()
                    .orElse(null));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
