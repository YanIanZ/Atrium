package dev.yanianz.atrium.module.display;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * The built-in placeholder values for one viewer at one moment. Every text value is inserted literally
 * (never parsed as MiniMessage), so a value that came from a player or another plugin cannot inject tags.
 * {@code displayName} is the server-side display name component (a nickname set by the nick module, else
 * the plain name) and is inserted as a component.
 */
public record PlaceholderValues(
        String player,
        Component displayName,
        String online,
        String max,
        String world,
        String ping,
        String tps,
        String balance,
        String playtime,
        String rank) {

    /** Tag names this record resolves; used in docs and config comments. */
    public static final String TAG_LIST =
            "<player> <displayname> <online> <max> <world> <ping> <tps> <balance> <playtime> <rank>";

    public TagResolver resolver() {
        return TagResolver.resolver(
                Placeholder.unparsed("player", nz(player)),
                Placeholder.component("displayname", displayName == null ? Component.text(nz(player)) : displayName),
                Placeholder.unparsed("online", nz(online)),
                Placeholder.unparsed("max", nz(max)),
                Placeholder.unparsed("world", nz(world)),
                Placeholder.unparsed("ping", nz(ping)),
                Placeholder.unparsed("tps", nz(tps)),
                Placeholder.unparsed("balance", nz(balance)),
                Placeholder.unparsed("playtime", nz(playtime)),
                Placeholder.unparsed("rank", nz(rank)));
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
