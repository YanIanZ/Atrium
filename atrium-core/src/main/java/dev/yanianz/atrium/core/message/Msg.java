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

    /** Untrusted text (player names, chat, item names typed by players): inserted literally. */
    public static TagResolver unparsed(String name, String value) {
        return Placeholder.unparsed(name, value == null ? "" : value);
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
