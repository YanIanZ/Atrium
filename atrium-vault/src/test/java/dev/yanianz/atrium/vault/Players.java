package dev.yanianz.atrium.vault;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.bukkit.OfflinePlayer;

/** OfflinePlayer stand-ins built with a dynamic proxy (only name and UUID are answered). */
final class Players {
    private Players() {
    }

    static OfflinePlayer offline(String name) {
        UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return (OfflinePlayer) Proxy.newProxyInstance(Players.class.getClassLoader(), new Class<?>[] {OfflinePlayer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getUniqueId" -> id;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "OfflinePlayer[" + name + "]";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
