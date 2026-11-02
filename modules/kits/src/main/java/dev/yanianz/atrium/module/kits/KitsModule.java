package dev.yanianz.atrium.module.kits;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.util.Durations;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;

/** In-game defined kits: claim, browse, edit, reset and administer. */
public final class KitsModule implements AtriumModule, Listener {
    private static final int MAX_USES_LIMIT = 1_000_000;

    private enum ResetKind { COOLDOWN, USES }

    private record PendingReset(ResetKind kind, String target, long expires) {
        boolean matches(ResetKind kind, String target) {
            return this.kind == kind && this.target.equalsIgnoreCase(target);
        }
    }

    private ModuleContext ctx;
    private ConfigHandle<KitsConfig> config;
    private KitService kits;
    private KitPreview preview;
    private KitEditor editor;
    private final Map<UUID, PendingReset> resetConfirmations = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "kits";
    }

    @Override
    public String description() {
        return "Kits with cooldowns, one-time use, costs and previews";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("economy");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.config = ctx.config(KitsConfig.class, KitsConfig::new);
        ctx.migrate(KitStore.MIGRATIONS);
        KitStore store = new KitStore(ctx.database());
        kits = new KitService(ctx, store, config::get);
        kits.load(store.loadKits().get(30, TimeUnit.SECONDS));
        ctx.component(store.usage);
        // SourbyCraft MultiProfile: kit cooldowns and use counts may follow the active profile.
        ctx.codec(store.usage, KitUsageCodec.INSTANCE);
        ctx.profileScoped(new dev.yanianz.atrium.core.profile.ComponentScopedData("kits", store.usage.id(),
                "Kit cooldowns and use counts", ctx.users()));
        preview = new KitPreview(ctx, kits, config::get);
        editor = new KitEditor(ctx, kits, this::registerPermission);
        ctx.listen(this);
        ctx.listen(editor);

        ctx.permission("atrium.kit", PermissionDefault.TRUE, "Use /kit");
        ctx.permission("atrium.kits", PermissionDefault.TRUE, "List kits");
        ctx.permission("atrium.kitpreview", PermissionDefault.TRUE, "Preview kits you may use");
        ctx.permission("atrium.kit.others", PermissionDefault.OP, "Give kits to other players");
        ctx.permission("atrium.kit.admin", PermissionDefault.OP, "Create, edit, delete and preview all kits");
        ctx.permission("atrium.kit.cdreset", PermissionDefault.OP, "Reset kit cooldowns for a player");
        ctx.permission("atrium.kit.usagereset", PermissionDefault.OP, "Reset kit usage counters for a player");
        ctx.permission("atrium.kit.bypass.cooldown", PermissionDefault.OP, "Ignore kit cooldowns");
        ctx.permission("atrium.kit.bypass.onetime", PermissionDefault.OP, "Claim one-time kits again");
        ctx.permission("atrium.kit.bypass.cost", PermissionDefault.OP, "Claim kits for free");
        ctx.permission("atrium.kit.bypass.maxuses", PermissionDefault.OP, "Ignore per-kit maximum uses");
        for (Kit kit : kits.kits()) registerPermission(kit);
        registerCommands();
        ctx.service(dev.yanianz.atrium.api.kits.KitAdminService.class, new KitAdmin(kits, kit -> ctx.scheduler().global(() -> registerPermission(kit))));
        ctx.logger().info("Loaded {} kits", kits.kits().size());
    }

    @Override
    public void disable() {
        // Preview menus are closed by the menu engine; the editor settles borrowed items itself.
        if (editor != null) editor.shutdown();
        resetConfirmations.clear();
    }

    private void registerPermission(Kit kit) {
        ctx.permission(kit.permission(), PermissionDefault.OP, "Use kit " + kit.name());
    }

    private boolean mayUse(CommandSender sender, Kit kit) {
        return sender.hasPermission(kit.permission()) || sender.hasPermission("atrium.kit.admin");
    }

    private SuggestionProvider<CommandSourceStack> usableKits() {
        return (c, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            CommandSender sender = c.getSource().getSender();
            for (Kit kit : kits.kits()) {
                if (kit.name().startsWith(remaining) && mayUse(sender, kit)) builder.suggest(kit.name());
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> allKits() {
        return Cmds.suggest(() -> Kit.sortedNames(kits.kits()));
    }

    private SuggestionProvider<CommandSourceStack> kitOrAll() {
        return Cmds.suggest(() -> {
            List<String> names = new java.util.ArrayList<>(Kit.sortedNames(kits.kits()));
            names.add("all");
            return names;
        });
    }

    private static SuggestionProvider<CommandSourceStack> boolSuggestions() {
        return Cmds.suggest(() -> List.of("true", "false"));
    }

    private void registerCommands() {
        ctx.command(CommandSpec.of(Cmds.literal("kit", "atrium.kit")
                .executes(this::list)
                .then(Commands.argument("name", StringArgumentType.word()).suggests(usableKits())
                        .executes(this::claimSelf)
                        .then(Commands.argument("player", ArgumentTypes.player())
                                .requires(Cmds.permission("atrium.kit.others"))
                                .executes(this::claimOther)))
                .build(), "Claim a kit, or give it to another player"));
        ctx.command(CommandSpec.of(Cmds.literal("kits", "atrium.kits").executes(this::list).build(),
                "List the kits you can use, with cooldowns and costs"));
        ctx.command(CommandSpec.of(Cmds.literal("kitpreview", "atrium.kitpreview")
                .executes(this::previewBrowser)
                .then(Commands.argument("name", StringArgumentType.word()).suggests(usableKits()).executes(this::preview))
                .build(), "Browse your kits or preview the items of one kit"));

        ctx.command(CommandSpec.of(Cmds.literal("kiteditor", "atrium.kit.admin")
                .executes(c -> usage(c, "kits.usage-kiteditor"))
                .then(Commands.argument("name", StringArgumentType.word()).suggests(allKits()).executes(this::openEditor))
                .build(), "Edit a kit's items and settings in a chest menu"));

        ctx.command(CommandSpec.of(Cmds.literal("createkit", "atrium.kit.admin")
                .executes(c -> usage(c, "kits.usage-createkit"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(c -> create(c, null))
                        .then(Commands.argument("cooldown", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> List.of("0", "30m", "1h", "12h", "1d", "7d")))
                                .executes(c -> create(c, StringArgumentType.getString(c, "cooldown")))))
                .build(), "Create a kit from your current inventory"));

        ctx.command(CommandSpec.of(Cmds.literal("deletekit", "atrium.kit.admin")
                .executes(c -> usage(c, "kits.usage-deletekit"))
                .then(Commands.argument("name", StringArgumentType.word()).suggests(allKits()).executes(this::delete))
                .build(), "Delete a kit permanently"));

        RequiredArgumentBuilder<CommandSourceStack, String> edit = Commands.argument("name", StringArgumentType.word()).suggests(allKits());
        edit.then(Cmds.literal("items").executes(this::editItems));
        edit.then(Cmds.literal("cooldown").executes(c -> usage(c, "kits.usage-editkit"))
                .then(Commands.argument("value", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> List.of("0", "30m", "1h", "12h", "1d", "7d"))).executes(c -> {
                    Duration d = Durations.parse(StringArgumentType.getString(c, "value"))
                            .orElseThrow(() -> fail(c, "kits.invalid-duration"));
                    return edit(c, k -> k.withCooldown(d.toSeconds(), System.currentTimeMillis()));
                })));
        edit.then(Cmds.literal("cost").executes(c -> usage(c, "kits.usage-editkit"))
                .then(Commands.argument("value", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> List.of("0", "100", "1000"))).executes(c -> {
                    BigDecimal cost = parseCost(c, StringArgumentType.getString(c, "value"));
                    return edit(c, k -> k.withCost(cost, System.currentTimeMillis()));
                })));
        edit.then(Cmds.literal("permission").executes(c -> usage(c, "kits.usage-editkit"))
                .then(Commands.argument("node", StringArgumentType.word())
                        .suggests((c, b) -> {
                            String name = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
                            if (Kit.validName(name)) b.suggest("atrium.kit." + name);
                            return b.buildFuture();
                        }).executes(c -> {
                    String node = StringArgumentType.getString(c, "node");
                    if (!Kit.validPermission(node)) throw fail(c, "kits.invalid-permission");
                    return edit(c, k -> k.withPermission(node, System.currentTimeMillis()));
                })));
        edit.then(Cmds.literal("maxuses").executes(c -> usage(c, "kits.usage-editkit"))
                .then(Commands.argument("value", IntegerArgumentType.integer(-1, MAX_USES_LIMIT))
                        .suggests(Cmds.suggest(() -> List.of("-1", "1", "5", "10")))
                        .executes(c -> edit(c, k -> k.withMaxUses(IntegerArgumentType.getInteger(c, "value"),
                                System.currentTimeMillis())))));
        edit.then(Cmds.literal("onetime").executes(c -> usage(c, "kits.usage-editkit"))
                .then(Commands.argument("value", BoolArgumentType.bool()).suggests(boolSuggestions()).executes(c -> {
                    boolean value = BoolArgumentType.getBool(c, "value");
                    return edit(c, k -> k.withOneTime(value, System.currentTimeMillis()));
                })));
        edit.then(Cmds.literal("firstjoin").executes(c -> usage(c, "kits.usage-editkit"))
                .then(Commands.argument("value", BoolArgumentType.bool()).suggests(boolSuggestions()).executes(c -> {
                    boolean value = BoolArgumentType.getBool(c, "value");
                    return edit(c, k -> k.withFirstJoin(value, System.currentTimeMillis()));
                })));
        edit.then(Cmds.literal("icon").executes(this::setIcon));
        edit.then(Cmds.literal("clearicon").executes(this::clearIcon));
        edit.executes(this::info);
        ctx.command(CommandSpec.of(Cmds.literal("editkit", "atrium.kit.admin")
                .executes(c -> usage(c, "kits.usage-editkit"))
                .then(edit).build(), "Show or change a kit's settings from chat"));

        registerReset("kitcdreset", "atrium.kit.cdreset", ResetKind.COOLDOWN);
        registerReset("kitusagereset", "atrium.kit.usagereset", ResetKind.USES);
    }

    private void registerReset(String literal, String permission, ResetKind kind) {
        String usageKey = kind == ResetKind.COOLDOWN ? "kits.usage-kitcdreset" : "kits.usage-kitusagereset";
        ctx.command(CommandSpec.of(Cmds.literal(literal, permission)
                .executes(c -> usage(c, usageKey))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(Cmds.knownPlayers(ctx.users()))
                        .executes(c -> reset(c, kind, "all"))
                        .then(Commands.argument("kit", StringArgumentType.word()).suggests(kitOrAll())
                                .executes(c -> reset(c, kind, StringArgumentType.getString(c, "kit")))))
                .build(), kind == ResetKind.COOLDOWN ? "Reset a player's kit cooldowns" : "Reset a player's kit usage counters"));
    }

    private int usage(CommandContext<CommandSourceStack> c, String key) {
        ctx.messages().send(c.getSource().getSender(), key);
        return Cmds.OK;
    }

    private CommandSyntaxException fail(CommandContext<CommandSourceStack> c, String key, TagResolver... tags) {
        return ctx.commands().fail(c.getSource(), key, tags);
    }

    private Kit kitArg(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String name = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
        return kits.kit(name).orElseThrow(() -> fail(c, "kits.unknown", Msg.unparsed("kit", name)));
    }

    private BigDecimal parseCost(CommandContext<CommandSourceStack> c, String text) throws CommandSyntaxException {
        try {
            BigDecimal value = new BigDecimal(text.trim());
            if (value.signum() >= 0) return value.stripTrailingZeros();
        } catch (NumberFormatException e) {
            // reported below
        }
        throw fail(c, "kits.invalid-value");
    }

    // ---- claiming ----

    private int claimSelf(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = ctx.commands().player(c.getSource());
        Kit kit = kitArg(c);
        if (!mayUse(player, kit)) {
            throw fail(c, "kits.no-permission", Msg.unparsed("kit", kit.name()));
        }
        UUIDPayer payer = payer(player, kit);
        kits.claim(player, kit, player, player, payer.uuid(), false);
        return Cmds.OK;
    }

    private int claimOther(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player target = ctx.commands().selfOrOther(c, "player", "atrium.kit.others");
        Kit kit = kitArg(c);
        CommandSender sender = c.getSource().getSender();
        if (!mayUse(sender, kit)) {
            throw fail(c, "kits.no-permission", Msg.unparsed("kit", kit.name()));
        }
        UUIDPayer payer = sender instanceof Player p ? payer(p, kit) : new UUIDPayer(null);
        kits.claim(target, kit, sender, sender, payer.uuid(), false);
        return Cmds.OK;
    }

    private record UUIDPayer(java.util.UUID uuid) {}

    /** The claiming player pays unless the kit is free or they bypass costs. */
    private static UUIDPayer payer(Player player, Kit kit) {
        return kit.chargeTo(player.hasPermission("atrium.kit.bypass.cost")).signum() <= 0
                ? new UUIDPayer(null)
                : new UUIDPayer(player.getUniqueId());
    }

    // ---- listing / preview ----

    private int list(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Map<String, KitUsage.Availability> statuses = new java.util.HashMap<>();
        List<Kit> visible = new java.util.ArrayList<>();
        for (Kit kit : kits.kits()) {
            if (!mayUse(sender, kit)) continue;
            if (sender instanceof Player player) {
                KitUsage.Availability availability = kits.availability(player, kit, null);
                statuses.put(kit.name(), availability);
                if (!availability.available() && !config.get().listUnavailable) continue;
            }
            visible.add(kit);
        }
        if (visible.isEmpty()) {
            ctx.messages().send(sender, "kits.list-empty");
            return Cmds.OK;
        }
        ctx.messages().send(sender, "kits.list-header", Msg.number("count", visible.size()));
        EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
        for (Kit kit : visible) {
            String costText = kit.cost().signum() > 0
                    ? (economy != null ? economy.format(kit.cost()) : kit.cost().toPlainString()) : "";
            TagResolver tags = TagResolver.resolver(Msg.unparsed("kit", kit.name()), Msg.unparsed("cost", costText),
                    Msg.unparsed("permission", kit.permission()));
            String key = "kits.list-available";
            Duration remaining = Duration.ZERO;
            KitUsage.Availability status = statuses.get(kit.name());
            if (status != null) {
                remaining = status.remaining();
                key = switch (status.status()) {
                    case AVAILABLE -> "kits.list-available";
                    case COOLDOWN -> "kits.list-cooldown";
                    case ALREADY_CLAIMED -> "kits.list-claimed";
                    case MAX_USES -> "kits.list-maxuses";
                };
            }
            Component line = ctx.messages().render(sender, key, tags, Msg.duration("time", remaining));
            if (kit.cost().signum() > 0) {
                line = line.append(ctx.messages().render(sender, "kits.list-cost", tags));
            }
            line = line.clickEvent(ClickEvent.runCommand("/kit " + kit.name()))
                    .hoverEvent(HoverEvent.showText(ctx.messages().render(sender, "kits.list-hover", tags)));
            sender.sendMessage(line);
        }
        if (sender instanceof Player player) {
            Component footer = ctx.messages().render(player, "kits.list-footer");
            player.sendMessage(footer.clickEvent(ClickEvent.runCommand("/kitpreview")));
        }
        return Cmds.OK;
    }

    private int previewBrowser(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = ctx.commands().player(c.getSource());
        try {
            preview.openBrowser(player);
        } catch (RuntimeException e) {
            ctx.logger().error("Cannot open the kit browser", e);
            throw fail(c, "kits.failed");
        }
        return Cmds.OK;
    }

    private int preview(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = ctx.commands().player(c.getSource());
        Kit kit = kitArg(c);
        if (!mayUse(player, kit)) {
            throw fail(c, "kits.no-permission", Msg.unparsed("kit", kit.name()));
        }
        try {
            preview.open(player, kit);
        } catch (RuntimeException e) {
            ctx.logger().error("Cannot preview kit {}", kit.name(), e);
            throw fail(c, "kits.failed");
        }
        return Cmds.OK;
    }

    // ---- administration ----

    private int openEditor(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = ctx.commands().player(c.getSource());
        Kit kit = kitArg(c);
        editor.open(player, kit);
        return Cmds.OK;
    }

    private int create(CommandContext<CommandSourceStack> c, String cooldownText) throws CommandSyntaxException {
        Player player = ctx.commands().player(c.getSource());
        String name = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
        if (!Kit.validName(name)) {
            throw fail(c, "kits.invalid-name");
        }
        long cooldown = 0;
        if (cooldownText != null) {
            cooldown = Durations.parse(cooldownText).orElseThrow(() -> fail(c, "kits.invalid-duration")).toSeconds();
        }
        if (kits.kit(name).isPresent()) {
            throw fail(c, "kits.exists", Msg.unparsed("kit", name));
        }
        ItemStack[] items = KitItems.capture(player.getInventory());
        if (KitItems.isEmpty(items)) {
            throw fail(c, "kits.empty-inventory");
        }
        Kit kit = new Kit(name, cooldown, false, false, BigDecimal.ZERO, KitItems.serialize(items), null, "", -1,
                System.currentTimeMillis());
        kits.store().createKit(kit).whenComplete((created, failure) -> {
            if (failure != null) {
                ctx.logger().error("Could not create kit {}", name, failure);
                kits.tell(player, "failed");
            } else if (!created) {
                kits.tell(player, "exists", Msg.unparsed("kit", name));
            } else {
                kits.put(kit);
                ctx.scheduler().global(() -> registerPermission(kit));
                kits.tell(player, "created", Msg.unparsed("kit", name),
                        Msg.duration("time", Duration.ofSeconds(kit.cooldownSeconds())),
                        Msg.unparsed("permission", kit.permission()));
            }
        });
        return Cmds.OK;
    }

    private int editItems(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = ctx.commands().player(c.getSource());
        ItemStack[] items = KitItems.capture(player.getInventory());
        if (KitItems.isEmpty(items)) {
            throw fail(c, "kits.empty-inventory");
        }
        byte[] bytes = KitItems.serialize(items);
        return edit(c, k -> k.withItems(bytes, System.currentTimeMillis()));
    }

    private int setIcon(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = ctx.commands().player(c.getSource());
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.isEmpty()) throw fail(c, "kits.empty-hand");
        byte[] icon = KitItems.serializeIcon(hand);
        return edit(c, k -> k.withIcon(icon, System.currentTimeMillis()));
    }

    private int clearIcon(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return edit(c, k -> k.withIcon(null, System.currentTimeMillis()));
    }

    private int edit(CommandContext<CommandSourceStack> c, UnaryOperator<Kit> change) throws CommandSyntaxException {
        Kit kit = kitArg(c);
        refuseWhileEditing(c, kit);
        CommandSender sender = c.getSource().getSender();
        Kit updated;
        try {
            updated = change.apply(kit);
        } catch (IllegalArgumentException e) {
            throw fail(c, "kits.invalid-value");
        }
        save(sender, updated);
        return Cmds.OK;
    }

    /** One writer per kit: commands that change or delete a kit are refused while its GUI editor is open. */
    private void refuseWhileEditing(CommandContext<CommandSourceStack> c, Kit kit) throws CommandSyntaxException {
        var holder = editor.editing(kit.name());
        if (holder.isPresent()) {
            throw fail(c, "kits.editor-in-use", Msg.unparsed("kit", kit.name()),
                    Msg.unparsed("player", holder.get().getName()));
        }
    }

    /** Versioned update: refused (nothing written) when the kit changed since {@code updated} was read. */
    private void save(CommandSender sender, Kit updated) {
        kits.store().updateKit(updated).whenComplete((stored, failure) -> {
            if (failure != null) {
                ctx.logger().error("Could not save kit {}", updated.name(), failure);
                kits.tell(sender, "failed");
                return;
            }
            if (stored.isEmpty()) {
                kits.refresh(updated.name());
                kits.tell(sender, "edit-conflict", Msg.unparsed("kit", updated.name()));
                return;
            }
            Kit saved = stored.get();
            kits.put(saved);
            ctx.scheduler().global(() -> registerPermission(saved));
            kits.tell(sender, "edited", Msg.unparsed("kit", saved.name()));
        });
    }

    private int info(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Kit kit = kitArg(c);
        ctx.messages().send(c.getSource().getSender(), "kits.info", Msg.unparsed("kit", kit.name()),
                Msg.duration("time", Duration.ofSeconds(kit.cooldownSeconds())),
                Msg.unparsed("onetime", Boolean.toString(kit.oneTime())),
                Msg.unparsed("firstjoin", Boolean.toString(kit.firstJoin())),
                Msg.unparsed("cost", kit.cost().toPlainString()),
                Msg.unparsed("permission", kit.permission()),
                Msg.unparsed("maxuses", kit.maxUses() < 0 ? "-1" : Integer.toString(kit.maxUses())));
        return Cmds.OK;
    }

    private int delete(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Kit kit = kitArg(c);
        refuseWhileEditing(c, kit);
        CommandSender sender = c.getSource().getSender();
        kits.store().deleteKit(kit.name(), kit.version()).whenComplete((removed, failure) -> {
            if (failure != null) {
                ctx.logger().error("Could not delete kit {}", kit.name(), failure);
                kits.tell(sender, "failed");
                return;
            }
            if (!removed) {
                kits.refresh(kit.name());
                kits.tell(sender, "edit-conflict", Msg.unparsed("kit", kit.name()));
                return;
            }
            kits.remove(kit.name());
            for (AtriumUser user : ctx.users().online()) {
                user.update(kits.store().usage, u -> u.with(kit.name(), null));
            }
            kits.tell(sender, "deleted", Msg.unparsed("kit", kit.name()));
        });
        return Cmds.OK;
    }

    // ---- resets ----

    private int reset(CommandContext<CommandSourceStack> c, ResetKind kind, String kitArg) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(c, "player");
        String kit = kitArg.toLowerCase(Locale.ROOT);
        boolean all = kit.equals("all");
        if (!all && kits.kit(kit).isEmpty()) {
            throw fail(c, "kits.unknown", Msg.unparsed("kit", kit));
        }
        CommandSender sender = c.getSource().getSender();
        if (all && sender instanceof Player player) {
            long now = System.nanoTime();
            PendingReset pending = resetConfirmations.get(player.getUniqueId());
            if (pending == null || !pending.matches(kind, targetName) || now - pending.expires() >= 0) {
                int seconds = Math.clamp(config.get().resetConfirmationSeconds, 1, 300);
                var next = new PendingReset(kind, targetName, now + TimeUnit.SECONDS.toNanos(seconds));
                resetConfirmations.put(player.getUniqueId(), next);
                ctx.scheduler().globalLater(() -> resetConfirmations.remove(player.getUniqueId(), next), seconds * 20L);
                kits.tell(sender, "reset-confirm", Msg.unparsed("player", targetName), Msg.number("seconds", seconds));
                return Cmds.OK;
            }
            resetConfirmations.remove(player.getUniqueId());
        }
        ctx.users().resolveAccount(targetName, false).thenCompose(ref -> {
            if (ref.isEmpty()) return CompletableFuture.completedFuture((AtriumUser) null);
            return ctx.users().load(ref.get().uuid()).thenApply(found -> found.orElse(null));
        }).whenComplete((user, failure) -> {
            if (failure != null) {
                ctx.logger().error("Could not resolve {} for a kit reset", targetName, failure);
                kits.tell(sender, "reset-failed");
                return;
            }
            if (user == null) {
                tellCore(sender, "core.user-not-found", Msg.unparsed("name", targetName));
                return;
            }
            user.update(kits.store().usage, u -> kind == ResetKind.COOLDOWN ? u.resetCooldown(all ? null : kit)
                    : u.resetUses(all ? null : kit));
            ctx.users().saveNow(user.uuid()).whenComplete((unused, saveFailure) -> {
                if (saveFailure != null) {
                    ctx.logger().error("Could not save kit reset for {}", user.name(), saveFailure);
                    kits.tell(sender, "reset-failed");
                    return;
                }
                kits.tell(sender, "reset-done", Msg.unparsed("player", user.name()), Msg.unparsed("kit", kit));
            });
        });
        return Cmds.OK;
    }

    private void tellCore(CommandSender sender, String key, TagResolver... tags) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
        } else {
            ctx.scheduler().global(() -> ctx.messages().send(sender, key, tags));
        }
    }

    // ---- first join ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPlayedBefore()) return;
        List<Kit> firstJoin = kits.kits().stream().filter(Kit::firstJoin).toList();
        if (firstJoin.isEmpty()) return;
        long delay = Math.max(1, config.get().firstJoinDelayTicks);
        ctx.track(ctx.scheduler().entityLater(player, () -> {
            CompletableFuture<KitService.Outcome> chain = CompletableFuture.completedFuture(KitService.Outcome.GIVEN);
            for (Kit kit : firstJoin) {
                // Sequential: the in-flight guard allows one claim per player at a time.
                chain = chain.thenCompose(previous -> kits.claim(player, kit, null, player, null, true));
            }
            chain.exceptionally(failure -> {
                ctx.logger().warn("First-join kits for {} failed", player.getName(), failure);
                return KitService.Outcome.FAILED;
            });
        }, null, delay));
    }
}
