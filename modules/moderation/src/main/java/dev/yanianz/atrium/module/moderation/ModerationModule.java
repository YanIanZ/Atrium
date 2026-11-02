package dev.yanianz.atrium.module.moderation;

import dev.yanianz.atrium.api.moderation.MuteService;
import dev.yanianz.atrium.api.moderation.PunishmentService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.bukkit.permissions.PermissionDefault;

/**
 * Bans, IP bans, kicks, mutes (normal and shadow), warnings with escalation, jails, freeze and a single
 * punishment history. Publishes {@link PunishmentService} and {@link MuteService}.
 */
public final class ModerationModule implements AtriumModule {
    private static final String[][] COMMAND_PERMISSIONS = {
            {"ban", "Ban players permanently"},
            {"tempban", "Ban players for a time"},
            {"ipban", "Ban IP addresses and ranges"},
            {"tempipban", "Temporarily ban IP addresses and ranges"},
            {"unban", "Lift bans"},
            {"unbanip", "Lift IP bans"},
            {"kick", "Kick players"},
            {"mute", "Mute players permanently"},
            {"tempmute", "Mute players for a time"},
            {"unmute", "Lift mutes"},
            {"warn", "Warn players"},
            {"warnings", "See your own active warnings"},
            {"delwarn", "Remove a single warning"},
            {"clearwarns", "Remove every warning of a player"},
            {"jail", "Jail players"},
            {"unjail", "Release players from jail"},
            {"setjail", "Create or move jails"},
            {"deljail", "Delete jails"},
            {"jails", "List jails"},
            {"jailedit", "Edit jail options (location, radius, release point, allowed commands, max sentence, chat)"},
            {"editwarnings", "Edit the warnings of players"},
            {"freeze", "Freeze and unfreeze players"},
            {"history", "See the punishment history of players"},
            {"banlist", "List active account bans"},
            {"ipbanlist", "List active IP bans"},
            {"checkban", "Show the details of a ban"},
            {"lockip", "Restrict the addresses an account may log in from"},
            {"note", "Read the staff notes of a player"},
            {"mutechat", "Mute chat for everyone"},
            {"unmutechat", "Lift the server-wide chat mute"},
    };

    private ModerationConfig cfg() {
        return config.get();
    }

    private ConfigHandle<ModerationConfig> config;
    private ModerationSync sync;
    private @org.jspecify.annotations.Nullable JailBossbar bossbar;

    @Override
    public String id() {
        return "moderation";
    }

    @Override
    public String description() {
        return "Bans, IP bans, kicks, mutes, warnings, jails, freeze and punishment history";
    }

    @Override
    public Set<String> softDependencies() {
        // Teleport (when present) moves jailed players with its safety checks and /back handling; proxy
        // carries the authenticated cross-server moderation topic.
        return Set.of("teleport", "proxy");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.config = ctx.config(ModerationConfig.class, ModerationConfig::new);
        ctx.migrate(PunishmentStore.MIGRATIONS);
        // v4 of the jail schema is applied separately: it inspects the table so it can be re-run after a
        // partially applied MySQL migration (see JailStore.ensureEditorColumns).
        JailStore.ensureEditorColumns(ctx.database()).get(30, TimeUnit.SECONDS);
        PunishmentStore store = new PunishmentStore(ctx.database());
        JailStore jails = new JailStore(ctx.database());
        jails.load().get(30, TimeUnit.SECONDS);
        PunishmentManager manager = new PunishmentManager(ctx, config, store, jails);

        for (String[] entry : COMMAND_PERMISSIONS) {
            ctx.permission(ModerationCommands.PERM + entry[0], PermissionDefault.OP, entry[1]);
        }
        ctx.permission(ModerationCommands.PERM + "warnings.others", PermissionDefault.OP, "See warnings of other players");
        ctx.permission(ModerationCommands.PERM + "editwarnings.clear", PermissionDefault.OP,
                "Clear every warning of a player with /editwarnings clear (separate from editing single warnings)");
        ctx.permission(ModerationCommands.PERM + "silent", PermissionDefault.OP, "Use -s to announce to staff only");
        ctx.permission(ModerationCommands.PERM + "shadowmute", PermissionDefault.OP, "Use -shadow with /mute and /tempmute");
        ctx.permission(ModerationCommands.PERM + "note.add", PermissionDefault.OP, "Write staff notes with /note <player> add");
        ctx.permission(ModerationCommands.PERM + "note.remove", PermissionDefault.OP,
                "Remove staff notes with /note <player> remove|clear");
        ctx.permission(ModerationCommands.PERM + "checkban.modify", PermissionDefault.OP,
                "Edit the reason or duration of an active ban with /checkban <player> edit");
        ctx.permission(PunishmentManager.PERM_IP, PermissionDefault.OP,
                "See exact IP addresses in moderation messages instead of the masked form");
        ctx.permission(ModerationListener.MUTECHAT_BYPASS, PermissionDefault.OP, "Talk while chat is muted");
        ctx.permission(BookLimits.PREFIX + ".unlimited", PermissionDefault.OP,
                "Never be limited by the book page limit (numeric nodes: " + BookLimits.PREFIX + ".N)");
        ctx.permission(ModerationCommands.PERM + "lockip.bypass", PermissionDefault.FALSE,
                "Online accounts with this node cannot be locked with /lockip");
        ctx.permission(PunishmentManager.NOTIFY, PermissionDefault.OP, "Receive staff-only (silent) moderation announcements");
        // Not OP by default: otherwise operators could not moderate each other. Grant explicitly to protected staff.
        ctx.permission(PunishmentManager.EXEMPT, PermissionDefault.FALSE,
                "Cannot be punished by anyone except the console");

        sync = new ModerationSync(ctx, config, manager);
        manager.sync(sync);
        sync.enable();

        // Players already online (module enabled late): load their active punishments into memory.
        for (org.bukkit.entity.Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            java.util.UUID uuid = online.getUniqueId();
            store.active(uuid, java.time.Instant.now()).thenAccept(list -> manager.cache().put(uuid, list))
                    .exceptionally(t -> {
                        ctx.logger().error("Could not load punishments of {}", online.getName(), t);
                        return null;
                    });
        }

        ctx.service(PunishmentService.class, manager);
        ctx.service(MuteService.class, manager);
        ctx.service(dev.yanianz.atrium.api.moderation.ModerationImportService.class, new ModerationImporter(manager, config));
        manager.loadMuteChat();
        bossbar = new JailBossbar(ctx, config, manager);
        bossbar.enable();
        ModerationListener listener = new ModerationListener(ctx, config, manager);
        listener.bossbar(bossbar);
        ctx.listen(listener);
        new ModerationCommands(ctx, config, manager).register();
        scheduleAfkPauses(ctx, manager);
        ctx.logger().info("Moderation ready: {} jails, {} escalation rules", jails.all().size(),
                cfg().warnings.escalation.size());
    }

    /** Pauses jail sentences of AFK prisoners; only registered when the sentence must not run while AFK. */
    private void scheduleAfkPauses(ModuleContext ctx, PunishmentManager manager) {
        if (config.get().jail.countWhileAfk) {
            return;
        }
        long seconds = Math.clamp(config.get().jail.afkCheckSeconds, 5, 3600);
        java.util.concurrent.atomic.AtomicLong last = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        ctx.track(ctx.scheduler().globalTimer(() -> {
            long now = System.nanoTime();
            long elapsed = now - last.getAndSet(now);
            manager.pauseAfkSentences(JailClock.afkExtension(elapsed));
        }, seconds * 20L, seconds * 20L));
    }

    @Override
    public void disable() {
        if (sync != null) {
            sync.disable();
        }
        if (bossbar != null) {
            bossbar.disable();
            bossbar = null;
        }
    }
}
