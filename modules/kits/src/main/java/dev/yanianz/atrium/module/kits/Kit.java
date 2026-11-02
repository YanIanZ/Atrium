package dev.yanianz.atrium.module.kits;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A kit defined in game. {@code items} is the serialized slot layout (see {@link KitItems}): storage 0-35,
 * armor 36-39 (boots, leggings, chestplate, helmet), offhand 40.
 *
 * @param cooldownSeconds 0 = no cooldown
 * @param cost            charged to the claiming player; zero = free
 * @param permission      node required to claim the kit; blank means {@code atrium.kit.<name>}
 * @param maxUses         -1 = unlimited, 0 = no uses allowed, positive = number of claims
 * @param version         optimistic-lock counter of the stored row; every successful update increments it
 */
public record Kit(String name, long cooldownSeconds, boolean oneTime, boolean firstJoin, BigDecimal cost, byte[] items,
        byte @Nullable [] icon, String permission, int maxUses, long updated, long version) {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern PERMISSION = Pattern.compile("[a-z0-9_.-]{1,100}");
    /** Names that would collide with permission nodes or sub commands. */
    static final Set<String> RESERVED = Set.of("others", "bypass", "admin", "preview", "list", "create", "edit",
            "delete", "editor", "cdreset", "usagereset", "permission", "icon", "maxuses", "save", "cancel");

    public Kit {
        name = name.toLowerCase(Locale.ROOT);
        if (cooldownSeconds < 0) throw new IllegalArgumentException("negative cooldown");
        if (cost.signum() < 0) throw new IllegalArgumentException("negative cost");
        if (maxUses < -1) throw new IllegalArgumentException("max uses must be -1 or more");
        items = items.clone();
        permission = permission == null || permission.isBlank()
                ? "atrium.kit." + name
                : permission.trim().toLowerCase(Locale.ROOT);
        if (!PERMISSION.matcher(permission).matches()) throw new IllegalArgumentException("invalid permission");
        icon = icon == null ? null : icon.clone();
        if (version < 0) throw new IllegalArgumentException("negative version");
    }

    /** A kit that has not been stored yet (version 0). */
    public Kit(String name, long cooldownSeconds, boolean oneTime, boolean firstJoin, BigDecimal cost, byte[] items,
            byte @Nullable [] icon, String permission, int maxUses, long updated) {
        this(name, cooldownSeconds, oneTime, firstJoin, cost, items, icon, permission, maxUses, updated, 0);
    }

    /** The same kit as stored with {@code value} as its row version. */
    public Kit withVersion(long value) {
        return new Kit(name, cooldownSeconds, oneTime, firstJoin, cost, items, icon, permission, maxUses, updated, value);
    }

    @Override
    public byte[] items() {
        return items.clone();
    }

    @Override
    public byte @Nullable [] icon() {
        return icon == null ? null : icon.clone();
    }

    public static boolean validName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return NAME.matcher(lower).matches() && !RESERVED.contains(lower);
    }

    /** Normalises and validates a permission node typed by an admin. */
    public static boolean validPermission(String node) {
        return node != null && PERMISSION.matcher(node.trim().toLowerCase(Locale.ROOT)).matches();
    }

    public String defaultPermission() {
        return "atrium.kit." + name;
    }

    /** The amount a claimant actually pays. */
    public BigDecimal chargeTo(boolean bypassCost) {
        return bypassCost || cost.signum() <= 0 ? BigDecimal.ZERO : cost;
    }

    public Kit withItems(byte[] value, long now) {
        return new Kit(name, cooldownSeconds, oneTime, firstJoin, cost, value, icon, permission, maxUses, now, version);
    }

    public Kit withCooldown(long seconds, long now) {
        return new Kit(name, seconds, oneTime, firstJoin, cost, items, icon, permission, maxUses, now, version);
    }

    public Kit withOneTime(boolean value, long now) {
        return new Kit(name, cooldownSeconds, value, firstJoin, cost, items, icon, permission, maxUses, now, version);
    }

    public Kit withFirstJoin(boolean value, long now) {
        return new Kit(name, cooldownSeconds, oneTime, value, cost, items, icon, permission, maxUses, now, version);
    }

    public Kit withCost(BigDecimal value, long now) {
        return new Kit(name, cooldownSeconds, oneTime, firstJoin, value, items, icon, permission, maxUses, now, version);
    }

    public Kit withPermission(String value, long now) {
        return new Kit(name, cooldownSeconds, oneTime, firstJoin, cost, items, icon, value, maxUses, now, version);
    }

    public Kit withMaxUses(int value, long now) {
        return new Kit(name, cooldownSeconds, oneTime, firstJoin, cost, items, icon, permission, value, now, version);
    }

    public Kit withIcon(byte @Nullable [] value, long now) {
        return new Kit(name, cooldownSeconds, oneTime, firstJoin, cost, items, value, permission, maxUses, now, version);
    }

    /** Replaces every editable setting at once (the GUI editor saves this way). */
    public Kit withSettings(long cooldown, boolean oneTimeValue, boolean firstJoinValue, BigDecimal costValue,
            byte[] itemBytes, byte @Nullable [] iconValue, String permissionValue, int maxUsesValue, long now) {
        return new Kit(name, cooldown, oneTimeValue, firstJoinValue, costValue, itemBytes, iconValue, permissionValue,
                maxUsesValue, now, version);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Kit k && k.name.equals(name) && k.cooldownSeconds == cooldownSeconds && k.oneTime == oneTime
                && k.firstJoin == firstJoin && k.cost.compareTo(cost) == 0 && Arrays.equals(k.items, items)
                && Arrays.equals(k.icon, icon) && k.permission.equals(permission) && k.maxUses == maxUses
                && k.updated == updated && k.version == version;
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + Arrays.hashCode(items);
    }

    @Override
    public String toString() {
        return "Kit[" + name + ", cooldown=" + cooldownSeconds + "s, oneTime=" + oneTime + ", firstJoin=" + firstJoin
                + ", cost=" + cost.toPlainString() + ", permission=" + permission + ", maxUses=" + maxUses + ", version=" + version + ", "
                + items.length + " bytes]";
    }

    static List<String> sortedNames(java.util.Collection<Kit> kits) {
        return kits.stream().map(Kit::name).sorted().toList();
    }
}
