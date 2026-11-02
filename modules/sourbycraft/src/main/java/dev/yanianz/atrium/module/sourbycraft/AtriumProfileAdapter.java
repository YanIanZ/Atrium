package dev.yanianz.atrium.module.sourbycraft;

import dev.iyanz.sourbycraft.api.profile.ProfileStateAdapter;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Atrium's one SourbyCraft profile state adapter. SourbyCraft calls it on the player's owning entity thread
 * inside its own capture/apply task; everything delegates to {@link ScopedStateBridge} and
 * {@link ProfileOwnership}, which are synchronous and in-memory. Hook class: loaded only on SourbyCraft.
 *
 * <p>Compiled against the official 26.2 API ({@code id, version, capture, apply, plugin}). {@link #required()}
 * and {@link #applyMissing(Player)} exist on newer local API builds only; they are deliberately plain methods
 * without {@code @Override}: such a server calls them, the official one never does.
 */
final class AtriumProfileAdapter implements ProfileStateAdapter {
    /** What the adapter reports to the hook. */
    interface Listener {
        /** After a capture (switch start, save or quit). */
        void captured(Player player);

        /** After a restore (apply or applyMissing). */
        void restored(Player player);
    }

    private final String id;
    private final boolean required;
    private final ScopedStateBridge bridge;
    private final ProfileOwnership ownership;
    private final Plugin plugin;
    private final Listener listener;

    AtriumProfileAdapter(String id, boolean required, ScopedStateBridge bridge, ProfileOwnership ownership,
            Plugin plugin, Listener listener) {
        this.id = id;
        this.required = required;
        this.bridge = bridge;
        this.ownership = ownership;
        this.plugin = plugin;
        this.listener = listener;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int version() {
        return ProfileEnvelope.FORMAT;
    }

    /**
     * Extended API only: a failing capture/apply aborts the switch. The official API logs and skips a failing
     * adapter instead.
     */
    public boolean required() {
        return required;
    }

    /** Never null: an explicit (possibly empty) envelope, so every profile visited has Atrium data. */
    @Override
    public byte[] capture(Player player) throws Exception {
        UUID uuid = player.getUniqueId();
        byte[] data = bridge.capture(uuid, ownership.beforeCapture(uuid));
        listener.captured(player);
        return data;
    }

    @Override
    public void apply(Player player, byte[] data) throws Exception {
        UUID uuid = player.getUniqueId();
        ownership.restored(uuid, bridge.apply(uuid, data));
        listener.restored(player);
    }

    /** Extended API only: the target profile has no Atrium data. */
    public void applyMissing(Player player) throws Exception {
        UUID uuid = player.getUniqueId();
        ownership.restored(uuid, bridge.applyMissing(uuid));
        listener.restored(player);
    }

    @Override
    public Plugin plugin() {
        return plugin;
    }
}
