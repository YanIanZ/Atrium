package dev.yanianz.atrium.module.warps;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Clickable chat editor for {@code /editwarp}. It shows every field and runs the same
 * {@link WarpManager} operations as {@code /setwarp}, {@code /renamewarp}, {@code /delwarp} and
 * {@code /setwarpicon}; no warp logic is duplicated here.
 *
 * <p>Every action applies the same ownership/permission rule as the command it mirrors
 * ({@link WarpEditPolicy}): {@code atrium.editwarp} opens the editor, but moving, renaming, re-categorising,
 * changing the permission or icon needs {@code atrium.setwarp} (or owning the warp) and deleting needs
 * {@code atrium.delwarp} (or owning it). Buttons for refused actions are not shown.
 *
 * <p>Destructive actions use a second clickable command: {@code /editwarp <warp> delete confirm}.
 * Warps without a creator (console or imported) are handled explicitly instead of dereferencing null.
 */
final class WarpEditor {
    private static final String NONE = "none";

    private final WarpsModule module;
    private final ModuleContext ctx;
    private final Cmds cmds;

    WarpEditor(WarpsModule module, ModuleContext ctx) {
        this.module = module;
        this.ctx = ctx;
        this.cmds = ctx.commands();
    }

    int usage(CommandContext<CommandSourceStack> c) {
        module.reply(c.getSource().getSender(), "warps.edit-usage");
        return Cmds.OK;
    }

    int show(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Warp warp = require(c);
        StoredLocation location = warp.location();
        module.reply(sender, "warps.edit-header", Msg.unparsed("warp", warp.name()));
        module.reply(sender, "warps.edit-location",
                Msg.unparsed("world", location.world()),
                Msg.number("x", location.blockX()), Msg.number("y", location.blockY()),
                Msg.number("z", location.blockZ()));
        module.reply(sender, "warps.edit-category", Msg.unparsed("category", warp.category()));
        if (warp.derivedPermission()) {
            module.reply(sender, "warps.edit-permission-derived", Msg.unparsed("permission", warp.permission()));
        } else {
            module.reply(sender, "warps.edit-permission", Msg.unparsed("permission", warp.permission()));
        }
        double cost = module.config().cost(warp.key());
        if (cost > 0) {
            module.reply(sender, "warps.edit-cost", Msg.unparsed("cost", number(cost)));
        } else {
            module.reply(sender, "warps.edit-cost-free");
        }
        long cooldown = module.config().cooldownSeconds(warp.key());
        module.reply(sender, "warps.edit-cooldown", Msg.number("seconds", cooldown));
        int extra = module.extraLocations(warp.key()).size();
        module.reply(sender, "warps.edit-locations", Msg.number("extra", extra),
                Msg.number("total", extra + 1), Msg.unparsed("mode",
                        module.config().randomMode == null ? "NON_REPEATING" : module.config().randomMode));
        WarpsConfig.Icon icon = module.config().icon(warp.key());
        if (icon == null) {
            module.reply(sender, "warps.edit-icon-default", Msg.unparsed("icon", module.config().defaultIcon));
        } else {
            module.reply(sender, "warps.edit-icon", Msg.unparsed("icon", icon.material),
                    Msg.number("model", icon.customModelData));
        }
        if (warp.creator() == null) {
            module.reply(sender, "warps.edit-owner-server");
        } else {
            String owner = ctx.users().cached(warp.creator()).map(AtriumUser::name)
                    .orElse(warp.creator().toString());
            module.reply(sender, "warps.edit-owner", Msg.unparsed("owner", owner),
                    Msg.unparsed("uuid", warp.creator().toString()));
        }
        List<Component> buttons = buttons(sender, warp);
        if (buttons.isEmpty()) {
            module.reply(sender, "warps.edit-no-actions");
        } else {
            Component actions = Component.join(JoinConfiguration.separator(Component.space()), buttons);
            module.reply(sender, "warps.edit-actions", Msg.component("actions", actions));
        }
        return Cmds.OK;
    }

    private List<Component> buttons(CommandSender sender, Warp target) {
        String warp = target.name();
        List<Component> buttons = new ArrayList<>();
        if (sender instanceof Player && may(sender, target, WarpEditPolicy.Action.MOVE)) {
            buttons.add(button(sender, "warps.edit-button-move", "warps.edit-hover-move",
                    ClickEvent.runCommand("/editwarp " + warp + " move")));
        }
        if (may(sender, target, WarpEditPolicy.Action.RENAME)) {
            buttons.add(button(sender, "warps.edit-button-rename", "warps.edit-hover-rename",
                    ClickEvent.suggestCommand("/editwarp " + warp + " rename ")));
        }
        if (may(sender, target, WarpEditPolicy.Action.CATEGORY)) {
            buttons.add(button(sender, "warps.edit-button-category", "warps.edit-hover-category",
                    ClickEvent.suggestCommand("/editwarp " + warp + " category ")));
        }
        if (may(sender, target, WarpEditPolicy.Action.PERMISSION)) {
            buttons.add(button(sender, "warps.edit-button-permission", "warps.edit-hover-permission",
                    ClickEvent.suggestCommand("/editwarp " + warp + " permission ")));
        }
        if (sender instanceof Player && may(sender, target, WarpEditPolicy.Action.ICON)) {
            buttons.add(button(sender, "warps.edit-button-icon", "warps.edit-hover-icon",
                    ClickEvent.runCommand("/editwarp " + warp + " icon")));
        }
        if (may(sender, target, WarpEditPolicy.Action.DELETE)) {
            buttons.add(button(sender, "warps.edit-button-delete", "warps.edit-hover-delete",
                    ClickEvent.runCommand("/editwarp " + warp + " delete")));
        }
        return buttons;
    }

    private static boolean may(CommandSender sender, Warp warp, WarpEditPolicy.Action action) {
        return WarpsModule.mayManage(sender, warp, action.adminPermission());
    }

    private Component button(CommandSender sender, String labelKey, String hoverKey, ClickEvent click) {
        return ctx.messages().render(sender, labelKey)
                .clickEvent(click)
                .hoverEvent(HoverEvent.showText(ctx.messages().render(sender, hoverKey)));
    }

    int move(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        Warp warp = require(c, WarpEditPolicy.Action.MOVE);
        StoredLocation target = StoredLocation.of(self.getLocation());
        module.report(self, module.manager().set(self, warp.name(), warp.category(), target, warp.creator(),
                        Integer.MAX_VALUE), warp.name(), null,
                Msg.unparsed("category", warp.category()), Msg.location("at", self.getLocation()),
                Msg.number("limit", -1));
        return Cmds.OK;
    }

    int rename(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Warp warp = require(c, WarpEditPolicy.Action.RENAME);
        String newName = StringArgumentType.getString(c, "new-name");
        module.report(sender, module.manager().rename(sender, warp.name(), newName), warp.name(), newName);
        return Cmds.OK;
    }

    int category(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Warp warp = require(c, WarpEditPolicy.Action.CATEGORY);
        String category = StringArgumentType.getString(c, "category");
        module.report(sender, module.manager().set(sender, warp.name(), category, warp.location(), warp.creator(),
                        Integer.MAX_VALUE), warp.name(), null,
                Msg.unparsed("category", category),
                Msg.number("at_x", warp.location().blockX()), Msg.number("at_y", warp.location().blockY()),
                Msg.number("at_z", warp.location().blockZ()), Msg.number("limit", -1));
        return Cmds.OK;
    }

    int permission(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Warp warp = require(c, WarpEditPolicy.Action.PERMISSION);
        String raw = StringArgumentType.getString(c, "node");
        String node = raw.equalsIgnoreCase(NONE) ? "" : raw;
        module.manager().setPermission(sender, warp.name(), node).whenComplete((outcome, error) -> {
            if (error != null) {
                ctx.logger().error("Saving the permission of warp {} failed", warp.name(), error);
                module.reply(sender, "warps.save-failed");
                return;
            }
            switch (outcome) {
                case MOVED -> {
                    if (node.isEmpty()) {
                        module.reply(sender, "warps.edit-permission-cleared", Msg.unparsed("warp", warp.name()));
                    } else {
                        module.reply(sender, "warps.edit-permission-set", Msg.unparsed("warp", warp.name()),
                                Msg.unparsed("permission", node));
                    }
                }
                case NOT_FOUND -> module.reply(sender, "warps.not-found", Msg.unparsed("warp", warp.name()));
                case INVALID_PERMISSION -> module.reply(sender, "warps.invalid-permission",
                        Msg.unparsed("permission", raw));
                case CANCELLED -> module.reply(sender, "warps.cancelled");
                default -> {
                    ctx.logger().warn("Unexpected outcome {} while setting the permission of warp {}",
                            outcome, warp.name());
                    module.reply(sender, "warps.save-failed");
                }
            }
        });
        return Cmds.OK;
    }

    int icon(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        Warp warp = require(c, WarpEditPolicy.Action.ICON);
        return module.applyIcon(self, warp);
    }

    // ---- cost, cooldown and random locations ----

    int costUsage(CommandContext<CommandSourceStack> c) {
        module.reply(c.getSource().getSender(), "warps.usage-editwarpcost");
        return Cmds.OK;
    }

    int cost(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Warp warp = require(c, WarpEditPolicy.Action.SETTINGS);
        double amount = number(StringArgumentType.getString(c, "amount"), 1_000_000_000d,
                () -> cmds.fail(c.getSource(), "warps.invalid-cost"));
        module.saveCost(warp, amount).whenComplete((saved, error) -> {
            if (error != null) {
                ctx.logger().error("Saving the cost of warp {} failed", warp.name(), error);
                module.reply(sender, "warps.save-failed");
                return;
            }
            module.reply(sender, "warps.edit-cost-set", Msg.unparsed("warp", warp.name()),
                    Msg.unparsed("cost", number(amount)));
        });
        return Cmds.OK;
    }

    int cooldownUsage(CommandContext<CommandSourceStack> c) {
        module.reply(c.getSource().getSender(), "warps.usage-editwarpcooldown");
        return Cmds.OK;
    }

    int cooldown(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Warp warp = require(c, WarpEditPolicy.Action.SETTINGS);
        long seconds = (long) number(StringArgumentType.getString(c, "seconds"), 604_800d,
                () -> cmds.fail(c.getSource(), "warps.invalid-cooldown"));
        module.saveCooldown(warp, seconds).whenComplete((saved, error) -> {
            if (error != null) {
                ctx.logger().error("Saving the cooldown of warp {} failed", warp.name(), error);
                module.reply(sender, "warps.save-failed");
                return;
            }
            module.reply(sender, "warps.edit-cooldown-set", Msg.unparsed("warp", warp.name()),
                    Msg.number("seconds", seconds));
        });
        return Cmds.OK;
    }

    int addLocation(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        Warp warp = require(c, WarpEditPolicy.Action.LOCATIONS);
        StoredLocation location = StoredLocation.of(self.getLocation());
        module.addExtraLocation(warp, location).whenComplete((count, error) -> {
            if (error != null) {
                ctx.logger().error("Adding a random location to warp {} failed", warp.name(), error);
                module.reply(self, "warps.save-failed");
                return;
            }
            if (count < 0) {
                module.reply(self, "warps.locations-full", Msg.number("max", SqlWarpLocations.MAX_LOCATIONS));
                return;
            }
            module.reply(self, "warps.locations-added", Msg.unparsed("warp", warp.name()),
                    Msg.number("count", count), Msg.number("total", count + 1),
                    Msg.unparsed("world", location.world()), Msg.number("x", location.blockX()),
                    Msg.number("y", location.blockY()), Msg.number("z", location.blockZ()));
        });
        return Cmds.OK;
    }

    int locations(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Warp warp = require(c);
        StoredLocation primary = warp.location();
        List<StoredLocation> extra = module.extraLocations(warp.key());
        module.reply(sender, "warps.locations-header", Msg.unparsed("warp", warp.name()),
                Msg.number("total", extra.size() + 1), Msg.unparsed("mode",
                module.config().randomMode == null ? "NON_REPEATING" : module.config().randomMode));
        module.reply(sender, "warps.locations-primary", Msg.number("index", 1),
                Msg.unparsed("world", primary.world()), Msg.number("x", primary.blockX()),
                Msg.number("y", primary.blockY()), Msg.number("z", primary.blockZ()));
        int index = 2;
        for (StoredLocation location : extra) {
            module.reply(sender, "warps.locations-entry", Msg.number("index", index++),
                    Msg.unparsed("world", location.world()), Msg.number("x", location.blockX()),
                    Msg.number("y", location.blockY()), Msg.number("z", location.blockZ()));
        }
        return Cmds.OK;
    }

    int clearLocationsConfirm(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Warp warp = require(c, WarpEditPolicy.Action.LOCATIONS);
        Component confirm = ctx.messages().render(sender, "warps.locations-clear-button")
                .clickEvent(ClickEvent.runCommand("/editwarp " + warp.name() + " clearlocations confirm"))
                .hoverEvent(HoverEvent.showText(ctx.messages().render(sender, "warps.locations-clear-hover")));
        module.reply(sender, "warps.locations-clear-confirm", Msg.unparsed("warp", warp.name()),
                Msg.component("confirm", confirm));
        return Cmds.OK;
    }

    int clearLocations(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Warp warp = require(c, WarpEditPolicy.Action.LOCATIONS);
        module.clearExtraLocations(warp).whenComplete((removed, error) -> {
            if (error != null) {
                ctx.logger().error("Removing the extra locations of warp {} failed", warp.name(), error);
                module.reply(sender, "warps.save-failed");
                return;
            }
            module.reply(sender, "warps.locations-cleared", Msg.unparsed("warp", warp.name()),
                    Msg.number("count", removed == null ? 0 : removed));
        });
        return Cmds.OK;
    }

    /**
     * Parses a non-negative decimal that must not exceed {@code max}. The failure is created lazily so the
     * command can report its own message key.
     */
    private static double number(String raw, double max, java.util.function.Supplier<CommandSyntaxException> fail)
            throws CommandSyntaxException {
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw fail.get();
        }
        if (!Double.isFinite(value) || value < 0 || value > max) {
            throw fail.get();
        }
        return value;
    }

    /** Plain text for a configured amount (no trailing ",0"). */
    private static String number(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    int deleteConfirm(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Warp warp = require(c, WarpEditPolicy.Action.DELETE);
        Component confirm = ctx.messages().render(sender, "warps.edit-confirm-button")
                .clickEvent(ClickEvent.runCommand("/editwarp " + warp.name() + " delete confirm"))
                .hoverEvent(HoverEvent.showText(ctx.messages().render(sender, "warps.edit-confirm-hover")));
        module.reply(sender, "warps.edit-delete-confirm", Msg.unparsed("warp", warp.name()),
                Msg.component("confirm", confirm));
        return Cmds.OK;
    }

    int delete(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Warp warp = require(c, WarpEditPolicy.Action.DELETE);
        module.report(sender, module.manager().delete(sender, warp.name()), warp.name(), null);
        return Cmds.OK;
    }

    private Warp require(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String name = StringArgumentType.getString(c, "warp");
        return module.manager().get(name)
                .orElseThrow(() -> cmds.fail(c.getSource(), "warps.not-found", Msg.unparsed("warp", name)));
    }

    /** The warp, if the sender may perform {@code action} on it (same rule as the stand-alone command). */
    private Warp require(CommandContext<CommandSourceStack> c, WarpEditPolicy.Action action) throws CommandSyntaxException {
        Warp warp = require(c);
        if (!may(c.getSource().getSender(), warp, action)) {
            throw cmds.fail(c.getSource(), "warps.edit-denied", Msg.unparsed("warp", warp.name()),
                    Msg.unparsed("permission", action.adminPermission()));
        }
        return warp;
    }
}
