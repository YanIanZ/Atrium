package dev.yanianz.atrium.module.itemtools;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jspecify.annotations.Nullable;

/**
 * The configured {@code /mobhead} library. Entries come from the module config only, never from players;
 * a broken entry is skipped with a log line instead of failing the command. A texture is applied to a
 * {@link Material#PLAYER_HEAD} through a local profile (no network lookup), so the menu works offline.
 */
final class MobHeads {
    private static final int MAX_TEXTURE_LENGTH = 8192;
    private static final int MAX_TEXTURE_BYTES = 4096;

    /** One usable head: {@code id} is lower case, {@code material} an item, {@code texture} empty or base64. */
    record Entry(String id, Material material, String texture) {
    }

    private MobHeads() {
    }

    static boolean validId(String id) {
        return id != null && id.matches("[a-z0-9_-]{1,32}");
    }

    static boolean validTexture(@Nullable String texture) {
        if (texture == null || texture.isBlank()) {
            return true;
        }
        String value = texture.strip();
        if (value.length() > MAX_TEXTURE_LENGTH) {
            return false;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return decoded.length > 0 && decoded.length <= MAX_TEXTURE_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Parses the config list; invalid entries are reported through {@code problem} and skipped. */
    static List<Entry> parse(List<ItemToolsConfig.MobHead> configured, java.util.function.Consumer<String> problem) {
        List<Entry> entries = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (configured == null) {
            return List.of();
        }
        for (ItemToolsConfig.MobHead head : configured) {
            if (head == null) {
                continue;
            }
            String id = head.id == null ? "" : head.id.strip().toLowerCase(Locale.ROOT);
            if (!validId(id)) {
                problem.accept("mobHeads entry has an invalid id '" + head.id
                        + "'; use lower-case letters, digits, _ or - (1-32)");
                continue;
            }
            if (!seen.add(id)) {
                problem.accept("mobHeads entry '" + id + "' is listed twice; the duplicate is ignored");
                continue;
            }
            Material material = head.material == null ? null : Material.matchMaterial(head.material.strip());
            if (material == null || material.isLegacy() || !isHead(material)) {
                problem.accept("mobHeads entry '" + id + "' has no head material ('" + head.material + "')");
                continue;
            }
            String texture = head.texture == null ? "" : head.texture.strip();
            if (!validTexture(texture)) {
                problem.accept("mobHeads entry '" + id + "' has an unreadable base64 texture");
                continue;
            }
            entries.add(new Entry(id, material, texture));
        }
        return List.copyOf(entries);
    }

    private static boolean isHead(Material material) {
        String name = material.name();
        return name.endsWith("_HEAD") || name.endsWith("_SKULL");
    }

    static Optional<Entry> find(List<Entry> entries, String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.strip().toLowerCase(Locale.ROOT);
        return entries.stream().filter(entry -> entry.id().equals(normalized)).findFirst();
    }

    /** A fresh head item; a copy is handed out on every click so the menu icon is never mutated. */
    static ItemStack item(Entry entry) {
        ItemStack item = new ItemStack(entry.material());
        if (entry.texture().isEmpty() || !(item.getItemMeta() instanceof SkullMeta meta)) {
            return item;
        }
        UUID id = UUID.nameUUIDFromBytes(("atrium:mobhead:" + entry.id()).getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Bukkit.createProfile(id, entry.id());
        profile.setProperty(new ProfileProperty("textures", entry.texture()));
        meta.setPlayerProfile(profile);
        item.setItemMeta(meta);
        return item;
    }
}
