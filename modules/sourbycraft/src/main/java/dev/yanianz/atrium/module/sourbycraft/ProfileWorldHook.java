package dev.yanianz.atrium.module.sourbycraft;

import dev.iyanz.sourbycraft.api.profile.AuroraProfiles;
import dev.iyanz.sourbycraft.api.profile.ProfileInfo;
import dev.iyanz.sourbycraft.api.profile.ProfileWorldRef;
import dev.yanianz.atrium.api.profile.ProfileWorldService;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;

/**
 * Publishes {@link ProfileWorldService} (worlds of the active profile, used by {@code /wtp profile}) while AWF
 * MultiProfile is enabled. Official 26.2 API only ({@code getActiveProfile}, {@code getProfileWorlds}). Hook
 * class: loaded only when the profile API classes exist.
 */
final class ProfileWorldHook implements ProfileWorldService {
    private final AuroraProfiles profiles;

    private ProfileWorldHook(AuroraProfiles profiles) {
        this.profiles = profiles;
    }

    /** True when the service was published. */
    static boolean start(SourbyCraftModule module) {
        ModuleContext ctx = module.context();
        AuroraProfiles profiles = Bukkit.getServicesManager().load(AuroraProfiles.class);
        if (profiles == null || !profiles.isEnabled()) {
            return false;
        }
        ctx.service(ProfileWorldService.class, new ProfileWorldHook(profiles));
        return true;
    }

    @Override
    public boolean enabled() {
        return profiles.isEnabled();
    }

    @Override
    public CompletableFuture<Optional<ProfileWorlds>> activeProfileWorlds(UUID player) {
        return profiles.getActiveProfile(player).thenCompose(active -> {
            if (active.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.<ProfileWorlds>empty());
            }
            ProfileInfo info = active.get();
            return profiles.getProfileWorlds(info.id())
                    .thenApply(refs -> Optional.of(new ProfileWorlds(info.id(), map(refs))));
        });
    }

    static List<ProfileWorld> map(List<ProfileWorldRef> refs) {
        return refs == null ? List.of() : refs.stream()
                .map(ref -> new ProfileWorld(ref.world(), ref.role(), ref.loaded()))
                .toList();
    }
}
