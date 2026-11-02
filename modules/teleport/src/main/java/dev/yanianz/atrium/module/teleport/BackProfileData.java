package dev.yanianz.atrium.module.teleport;

import dev.yanianz.atrium.api.profile.ProfileScopedData;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * The /back history as profile-scoped data (SourbyCraft MultiProfile). Only the history follows the
 * profile; the /tptoggle preference in the same component stays account-wide.
 */
final class BackProfileData implements ProfileScopedData {
    static final String ID = "back";

    private final ModuleContext ctx;
    private final TeleportStore store;

    BackProfileData(ModuleContext ctx, TeleportStore store) {
        this.ctx = ctx;
        this.store = store;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "/back history";
    }

    @Override
    public byte @Nullable [] capture(UUID player) {
        BackHistory history = user(player).get(store.component).back();
        return encode(history);
    }

    @Override
    public CompletableFuture<Void> apply(UUID player, byte @Nullable [] data) {
        BackHistory history = decode(data);
        user(player).update(store.component, state -> state.withBack(history));
        return ctx.users().saveNow(player);
    }

    private AtriumUser user(UUID player) {
        return ctx.users().cached(player)
                .orElseThrow(() -> new IllegalStateException("Teleport data of " + player + " is not loaded"));
    }

    /** {@code null} for an empty history, else the locale-independent text form as UTF-8. */
    static byte @Nullable [] encode(BackHistory history) {
        return history.isEmpty() ? null : history.encode().getBytes(StandardCharsets.UTF_8);
    }

    static BackHistory decode(byte @Nullable [] data) {
        return data == null || data.length == 0 ? BackHistory.EMPTY : BackHistory.decode(new String(data, StandardCharsets.UTF_8));
    }
}
