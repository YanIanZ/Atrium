package dev.yanianz.atrium.module.sourbycraft;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/sourbycraft.yml. Without SourbyCraft (plain Paper/Folia) the module stays idle. */
@ConfigSerializable
public class SourbyCraftConfig {
    @Comment("Register Atrium's profile state adapter when SourbyCraft AWF MultiProfile is enabled\n"
            + "(sourbycraft_config/aurora.yml -> aurora.awf.multiprofile.enabled). Restart to apply.")
    public boolean multiprofile = true;

    @Comment("Atrium data that follows the player's active profile. Known ids: homes, back (the /back history),\n"
            + "kits (kit cooldowns and use counts). Everything else stays account-wide: nick, mail, moderation,\n"
            + "playtime, chat settings, particles, /tptoggle and the Atrium economy (see economy below).\n"
            + "The data is stored inside the SourbyCraft profile snapshot (one adapter, at most 2 MiB) and is\n"
            + "written to Atrium's database on every switch, so Atrium always holds the active profile's data.")
    public List<String> profileScoped = new ArrayList<>(List.of("homes", "back", "kits"));

    @Comment("Adapter id in the SourbyCraft snapshot ([a-z0-9_.:-]). Never reuse an id for different data.")
    public String profileAdapterId = "atrium:profile";

    @Comment("true = a failed capture/restore of Atrium data aborts the profile switch or login (SourbyCraft rolls\n"
            + "back), so two profiles' data are never mixed. false = failures are logged and skipped.")
    public boolean profileAdapterRequired = true;

    @Comment("A profile that has no stored Atrium data yet (new profile, or created before Atrium was installed):\n"
            + "EMPTY = start with no homes/back/kit usage (SourbyCraft's applyMissing semantics, nothing is inherited\n"
            + "from the previous profile). KEEP = keep the live data once (only for migrating an existing server).")
    public String profileMissingState = "EMPTY";

    @Comment("Seconds between background refreshes of the active profile shown by %atrium_profile*% and\n"
            + "/atrium profile (5-600). The profile is also refreshed on join and after every switch.")
    public int profileInfoRefreshSeconds = 30;

    @Comment("Log at startup how money relates to profiles. Atrium's economy is NEVER profile-keyed: balances stay\n"
            + "per account. When the SSB-AuroraProfiles purse uses economy.backend auto/vault and Atrium is the\n"
            + "Vault provider, the purse's own required adapter swaps the balance on every switch by writing it\n"
            + "through Vault into Atrium; keying Atrium by profile as well would manage the same money twice.")
    public boolean economyStartupCheck = true;

    @Comment("Load an AWF world (SourbyCraft Aurora World Fabric) on demand when a home, warp or /back points\n"
            + "into it and it is not loaded. Plain worlds that are not loaded are never loaded by Atrium.")
    public boolean worldsLoadOnDemand = true;

    @Comment("Seconds to wait for an on-demand world load before giving up with a 'try again' message (5-120).")
    public int worldsLoadTimeoutSeconds = 30;

    @Comment("AWF worlds that are never loaded on demand (exact names, for example templates' working copies).")
    public List<String> worldsOnDemandDeny = new ArrayList<>();

    @Comment("Turn SourbyCraft's WorldUnloadAsyncEvent into Atrium's WorldUnloadingEvent, so pending teleports\n"
            + "into an unloading world are cancelled with a message.")
    public boolean worldUnloadNotify = true;

    @Comment("Show SourbyCraft's own region metrics (worst/median region TPS, p95 MSPT) in /lag.")
    public boolean regionMetrics = true;
}
