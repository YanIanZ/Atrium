package dev.yanianz.atrium.core.message;

import java.math.BigDecimal;
import java.time.Duration;
import dev.yanianz.atrium.core.util.Durations;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;

/** Short helpers for message placeholders. */
public final class Msg {
    private Msg() {}

    /**
     * The name other players may see for someone: the account name, or for a disguised player (WuretaDisguise
     * marks them with the metadata {@code wureta_disguised}, or {@code wureta_nick} for a name-only disguise) the
     * name they show. Use it for every name that goes
     * into a message; keep {@code getName()} for logs, storage and command arguments.
     */
    public static String shownName(org.bukkit.entity.Player player) {
        if (player == null) return "";
        try {
            if (player.hasMetadata("wureta_disguised") || player.hasMetadata("wureta_nick")) {
                String shown = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(player.displayName());
                if (!shown.isBlank()) return shown;
            }
        } catch (RuntimeException ignored) {
            // fall through to the account name
        }
        return player.getName();
    }

    public static String shownName(org.bukkit.command.CommandSender sender) {
        return sender instanceof org.bukkit.entity.Player player ? shownName(player) : sender == null ? "" : sender.getName();
    }

    public static String shownName(org.bukkit.OfflinePlayer player) {
        if (player == null) return "";
        org.bukkit.entity.Player online = player.getPlayer();
        if (online != null) return shownName(online);
        String name = player.getName();
        return name == null ? "" : name;
    }

    /** Untrusted text (player names, chat, item names typed by players): inserted literally. */
    public static TagResolver unparsed(String name, String value) {
        return Placeholder.unparsed(name, value == null ? "" : value);
    }

    /**
     * A styling tag that opens {@code url} when clicked: {@code <name>text</name>}. Use it instead of
     * {@code <click:open_url:'<url>'>}, because MiniMessage never resolves placeholders inside tag arguments.
     * Only {@code https://} URLs get a click action; anything else becomes a plain styling tag.
     */
    public static TagResolver link(String name, String url) {
        if (url == null || !url.regionMatches(true, 0, "https://", 0, 8)) {
            return Placeholder.styling(name);
        }
        return Placeholder.styling(name, net.kyori.adventure.text.event.ClickEvent.openUrl(url),
                net.kyori.adventure.text.event.HoverEvent.showText(Component.text(url)));
    }

    public static TagResolver component(String name, Component value) {
        return Placeholder.component(name, value);
    }

    public static TagResolver number(String name, long value) {
        return Placeholder.unparsed(name, Long.toString(value));
    }

    public static TagResolver number(String name, double value) {
        return Placeholder.unparsed(name, BigDecimal.valueOf(value).stripTrailingZeros().toPlainString());
    }

    public static TagResolver duration(String name, Duration value) {
        return Placeholder.unparsed(name, Durations.format(value, 3));
    }

    public static TagResolver location(String prefix, Location location) {
        return TagResolver.resolver(
                Placeholder.unparsed(prefix + "_world", location.getWorld() == null ? "?" : location.getWorld().getName()),
                Placeholder.unparsed(prefix + "_x", Integer.toString(location.getBlockX())),
                Placeholder.unparsed(prefix + "_y", Integer.toString(location.getBlockY())),
                Placeholder.unparsed(prefix + "_z", Integer.toString(location.getBlockZ())));
    }
}
