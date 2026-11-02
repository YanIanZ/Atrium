package dev.yanianz.atrium.module.itemtools;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/itemtools.yml */
@ConfigSerializable
public class ItemToolsConfig {
    @Comment("Seconds an unanswered /donate offer stays valid (clamped to 5-600).")
    public int donateOfferSeconds = 60;

    @Comment("Maximum unanswered /donate offers one player may receive at once (clamped to 1-50).\n"
            + "A new offer from the same donor with a different item is refused instead of silently replacing it.")
    public int donateMaxPending = 10;

    @Comment("A /giveall amount above this needs the same command twice within confirmationSeconds (clamped to 1-2304).")
    public int giveallConfirmAbove = 64;

    @Comment("Seconds a destructive-command confirmation stays valid (clamped to 3-120).")
    public int confirmationSeconds = 15;

    @Comment("Upper limit for /giveall amounts (clamped to 1-3456).")
    public int giveallMaxAmount = 2304;

    @Comment("Skip condensing items that carry a custom name, lore, enchantments, CustomModelData,\n"
            + "persistent data, item flags, damage or a repair cost. This prevents losing customised items.")
    public boolean condenseSkipCustomised = true;

    @Comment("How far (in blocks) /itemframe looks for the frame the player is aiming at (clamped to 1-32).")
    public double frameRange = 10.0;

    @Comment("Master switch for the low-durability warning. Players still have to opt in once with\n"
            + "/durabilitywarn; this switch lets the server owner silence it for everyone.")
    public boolean durabilityWarnings = true;

    @Comment("Warn when the durability left after a hit is at or below this percentage of the maximum\n"
            + "(clamped to 1-90).")
    public int durabilityThresholdPercent = 10;

    @Comment("Seconds between two warnings for the same player and item (clamped to 5-3600).")
    public int durabilityWarnCooldownSeconds = 60;

    @Comment("Keep the name, lore, enchantments and other item data of a skull through place and break.\n"
            + "Vanilla drops a plain skull; with this on the exact item that was placed comes back. The data\n"
            + "is stored in the placed block itself and disappears with it, so no skull can inherit it.")
    public boolean preserveSkullData = true;

    @Comment("Most action lines one item may carry (clamped to 1-64).")
    public int attachedMaxLines = 16;

    @Comment("Milliseconds two triggers of the same attached command item are at least apart\n"
            + "(clamped to 50-2000). A double click never runs the same lines twice.")
    public int attachedMinIntervalMillis = 100;

    @Comment("Heads listed by /mobhead. id: name used in the command and menu.\n"
            + "material: any head or skull item, e.g. PLAYER_HEAD, ZOMBIE_HEAD, SKELETON_SKULL.\n"
            + "texture: optional base64 'textures' value for PLAYER_HEAD; empty keeps the material's own skin.")
    public List<MobHead> mobHeads = new ArrayList<>(List.of(
            new MobHead("zombie", "ZOMBIE_HEAD", ""),
            new MobHead("skeleton", "SKELETON_SKULL", ""),
            new MobHead("wither-skeleton", "WITHER_SKELETON_SKULL", ""),
            new MobHead("creeper", "CREEPER_HEAD", ""),
            new MobHead("piglin", "PIGLIN_HEAD", ""),
            new MobHead("dragon", "DRAGON_HEAD", ""),
            new MobHead("player", "PLAYER_HEAD", "")));

    @Comment("Folder for /openbook text files, relative to plugins/Atrium/data/itemtools/.\n"
            + "A file is named after the book (rules.txt) and split into pages by a line containing only ---.")
    public String bookFolder = "books";

    @Comment("Author shown inside a book opened by /openbook.")
    public String bookAuthor = "Server";

    @Comment("Most offline accounts one /giveall ... offline queues for (clamped to 1-100000).\n"
            + "Online players always receive the item directly and are not queued.")
    public int giveallOfflineLimit = 5000;

    @Comment("Most copies /saveditems get and /saveditems give accept in one call (clamped to 1-2304).")
    public int savedItemsMaxGive = 64;

    @ConfigSerializable
    public static class MobHead {
        @Comment("Identifier used by /mobhead <id> (lower-case letters, digits, _ and -).")
        public String id = "";
        @Comment("Head material, e.g. PLAYER_HEAD or ZOMBIE_HEAD.")
        public String material = "PLAYER_HEAD";
        @Comment("Optional base64 'textures' value for PLAYER_HEAD; empty uses the default skin.")
        public String texture = "";

        public MobHead() {
        }

        public MobHead(String id, String material, String texture) {
            this.id = id;
            this.material = material;
            this.texture = texture;
        }
    }

    int offerSeconds() {
        return Math.clamp(donateOfferSeconds, 5, 600);
    }

    int maxPending() {
        return Math.clamp(donateMaxPending, 1, 50);
    }

    int confirmAbove() {
        return Math.clamp(giveallConfirmAbove, 1, 2304);
    }

    int confirmationSeconds() {
        return Math.clamp(confirmationSeconds, 3, 120);
    }

    int maxGiveallAmount() {
        return Math.clamp(giveallMaxAmount, 1, 3456);
    }

    double frameRange() {
        return Math.clamp(Double.isFinite(frameRange) ? frameRange : 10.0, 1.0, 32.0);
    }

    int durabilityThreshold() {
        return ItemDurability.threshold(durabilityThresholdPercent);
    }

    int durabilityCooldownSeconds() {
        return Math.clamp(durabilityWarnCooldownSeconds, 5, 3600);
    }

    int maxAttachedLines() {
        return Math.clamp(attachedMaxLines, 1, 64);
    }

    int attachedGateMillis() {
        return Math.clamp(attachedMinIntervalMillis, 50, 2000);
    }

    int offlineLimit() {
        return Math.clamp(giveallOfflineLimit, 1, 100_000);
    }

    int savedItemsGiveLimit() {
        return Math.clamp(savedItemsMaxGive, 1, 2304);
    }

    String bookFolderName() {
        if (bookFolder == null || bookFolder.isBlank()) {
            return "books";
        }
        String cleaned = bookFolder.replaceAll("[^A-Za-z0-9_-]", "");
        return cleaned.isEmpty() ? "books" : cleaned;
    }

    String bookAuthorName() {
        return bookAuthor == null || bookAuthor.isBlank() ? "Server" : bookAuthor.strip();
    }
}
