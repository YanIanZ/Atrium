package dev.yanianz.atrium.module.webstore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/webstore.yml */
@ConfigSerializable
public class WebstoreConfig {
    @Comment("Seconds between two checks of the deliveries table for pending orders (2-3600).")
    public int pollIntervalSeconds = 10;

    @Comment("How often a delivery is tried before it is marked failed (1-100). A failed order can be retried with\n"
            + "/webstore retry <order_id>.")
    public int maxAttempts = 5;

    @Comment("Minutes after which a delivery claimed by a server that stopped or hung is given back to the queue\n"
            + "(1-1440). Only matters when a server crashes in the middle of a delivery.")
    public int staleClaimMinutes = 10;

    @Comment("Deliver only while the recipient is online on this server. Offline recipients stay pending (no attempt\n"
            + "is used) until they join. With false, commands run immediately; LuckPerms works for offline players.")
    public boolean requireOnline = false;

    @Comment("Announce every delivery to all online players (webstore.broadcast / webstore.broadcast-gift messages).")
    public boolean broadcast = false;

    @Comment("""
            Store packages by package_id (the value the website writes into the package_id column).
              display-name: MiniMessage shown to players (trusted, admin-written).
              commands: console commands, run in order on the global region thread. Placeholders:
                {uuid} recipient UUID, {player} recipient name, {buyer} buyer name, {package} package id.
                Values are checked before they are inserted (UUID format, Minecraft name, [A-Za-z0-9_.-]);
                a missing or unsafe value fails the delivery instead of running the command.
                A command that reports failure (for example an unknown command) fails the attempt; it is retried,
                and earlier commands of the package run again, so keep them idempotent.
            Unknown package ids are marked failed with last_error.""")
    public Map<String, StorePackage> packages = defaultPackages();

    int pollSeconds() {
        return Math.clamp(pollIntervalSeconds, 2, 3600);
    }

    int attempts() {
        return Math.clamp(maxAttempts, 1, 100);
    }

    long staleClaimMillis() {
        return Math.clamp(staleClaimMinutes, 1, 1440) * 60_000L;
    }

    /** One package and what delivering it does. */
    @ConfigSerializable
    public static class StorePackage {
        @Comment("Name shown to players (MiniMessage).")
        public String displayName = "";

        @Comment("Console commands run on delivery, in order.")
        public List<String> commands = new ArrayList<>();

        static StorePackage of(String displayName, String... commands) {
            StorePackage p = new StorePackage();
            p.displayName = displayName;
            p.commands = new ArrayList<>(List.of(commands));
            return p;
        }
    }

    private static Map<String, StorePackage> defaultPackages() {
        Map<String, StorePackage> map = new LinkedHashMap<>();
        map.put("vip", StorePackage.of("<green><bold>VIP</bold></green>", "lp user {uuid} parent add vip"));
        map.put("mvp", StorePackage.of("<aqua><bold>MVP</bold></aqua>", "lp user {uuid} parent add mvp"));
        map.put("mvp_plus", StorePackage.of("<aqua><bold>MVP</bold></aqua><gold><bold>+</bold></gold>",
                "lp user {uuid} parent add mvp_plus"));
        map.put("ultra", StorePackage.of("<light_purple><bold>ULTRA</bold></light_purple>", "lp user {uuid} parent add ultra"));
        map.put("supreme", StorePackage.of("<gold><bold>SUPREME</bold></gold>", "lp user {uuid} parent add supreme"));
        map.put("zenith", StorePackage.of("<gradient:#ffd36b:#ff6b9d><bold>ZENITH</bold></gradient>",
                "lp user {uuid} parent add zenith"));
        return map;
    }
}
