package dev.yanianz.atrium.module.integrations;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/integrations.yml */
@ConfigSerializable
public class IntegrationsConfig {
    @Comment("Use WorldGuard regions for protection checks.")
    public boolean worldGuard = true;

    @Comment("Use GriefPrevention claims for protection checks.")
    public boolean griefPrevention = true;

    @Comment("Use Lands areas for protection checks.")
    public boolean lands = true;

    @Comment("Use Towny towns for protection checks.")
    public boolean towny = true;

    @Comment("Check block placement and block changes (ProtectionService.canBuild).")
    public boolean checkBuild = true;

    @Comment("Check block breaking (ProtectionService.canBreak).")
    public boolean checkBreak = true;

    @Comment("Check block/entity interaction (ProtectionService.canInteract).")
    public boolean checkInteract = true;

    @Comment("Check WorldGuard's blocked-cmds/allowed-cmds region flags (ProtectionService.canUseCommandAt).")
    public boolean checkCommands = true;

    @Comment("Metadata provider: auto, luckperms, vault or none.\n"
            + "auto = LuckPerms when enabled, else Vault Chat, else empty values.")
    public String metaProvider = "auto";

    @Comment("Also show integrations whose plugin is not installed or is disabled in /integrations.")
    public boolean showMissing = true;

    @Comment("Log one line per protection hook at startup (name, version, active).")
    public boolean logHooks = true;

    boolean protectionHook(String id) {
        return switch (id) {
            case "worldguard" -> worldGuard;
            case "griefprevention" -> griefPrevention;
            case "lands" -> lands;
            case "towny" -> towny;
            default -> false;
        };
    }

    /** Explicit result; the caller still checks that the chosen plugin is available. */
    MetaBackend metaBackend() {
        String value = metaProvider == null ? "auto" : metaProvider.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "luckperms" -> MetaBackend.LUCKPERMS;
            case "vault" -> MetaBackend.VAULT;
            case "none" -> MetaBackend.NONE;
            default -> MetaBackend.AUTO;
        };
    }

    enum MetaBackend { AUTO, LUCKPERMS, VAULT, NONE }
}
