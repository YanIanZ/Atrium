package dev.yanianz.atrium.module.chatbubbles;

import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.function.Supplier;
import org.bukkit.Bukkit;

/**
 * Loads {@link PacketBubbleRenderer} only when the packetevents plugin is enabled. Keeping the lookup in
 * this tiny class means a server without PacketEvents never resolves a PacketEvents class, so the optional
 * dependency cannot cause a {@link NoClassDefFoundError} in the chatbubbles module.
 */
final class PacketBubbleFactory {
    static final String PLUGIN = "packetevents";

    private PacketBubbleFactory() {}

    /** @return a ready renderer, or {@code null} when packets cannot be used on this server */
    static PacketBubbleRenderer create(ModuleContext ctx, Supplier<ChatBubblesConfig> config) {
        // Checked first: never touch PacketEvents before this.
        if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
            return null;
        }
        try {
            return PacketBubbleRenderer.create(ctx, config);
        } catch (RuntimeException | LinkageError e) {
            // A broken or incompatible PacketEvents must not take the whole module down.
            ctx.logger().warn("PacketEvents is enabled but its API cannot be used; chat bubbles use follow mode", e);
            return null;
        }
    }
}
