package dev.yanianz.atrium.module.kits;

import dev.yanianz.atrium.api.kits.KitAdminService;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * One-time import of the kits of the PlayerKits2 plugin ({@code plugins/PlayerKits2/kits/<name>.yml}).
 *
 * <p>Carried over: the items exactly as PlayerKits2 stored them ({@code items.<n>.original}, in order, into the
 * storage slots), the cooldown, the one-time flag, the menu icon's material and the permission. A kit that needed a
 * permission keeps PlayerKits2's node ({@code playerkits.kit.<name>}, or its custom node), so existing rank
 * permissions keep working. Not carried over: requirements/prices, claim actions and the menu layout. Player
 * cooldowns are not read. The PlayerKits2 files are never changed.
 */
final class PlayerKitsImport {
    private final ModuleContext ctx;
    private final KitAdminService admin;

    PlayerKitsImport(ModuleContext ctx, KitAdminService admin) {
        this.ctx = ctx;
        this.admin = admin;
    }

    void run(CommandSender sender, boolean overwrite) {
        File folder = new File(ctx.plugin().getDataFolder().getParentFile(), "PlayerKits2/kits");
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            sender.sendMessage("No PlayerKits2 kits found in " + folder.getPath());
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            String name = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                List<ItemStack> slots = new ArrayList<>();
                int skipped = 0;
                ConfigurationSection items = yaml.getConfigurationSection("items");
                if (items != null) {
                    List<String> keys = new ArrayList<>(items.getKeys(false));
                    keys.sort(Comparator.comparingInt(PlayerKitsImport::number));
                    for (String key : keys) {
                        ItemStack item = items.getItemStack(key + ".original");
                        if (item == null || item.isEmpty()) {
                            skipped++;
                        } else if (slots.size() < 36) {
                            slots.add(item);
                        } else {
                            skipped++;
                        }
                    }
                }
                if (slots.isEmpty()) {
                    sender.sendMessage("Kit " + name + ": no stored items (items.<n>.original), skipped.");
                    continue;
                }
                String permission = null;
                if (yaml.getBoolean("permission_required", false)) {
                    String custom = yaml.getString("custom_permission");
                    permission = custom == null || custom.isBlank() ? "playerkits.kit." + name : custom.trim();
                }
                Material material = Material.matchMaterial(String.valueOf(yaml.getString("display.default.id", "")));
                ItemStack icon = material == null || !material.isItem() || material.isAir() ? null : new ItemStack(material);
                boolean oneTime = yaml.getBoolean("one_time", false);
                int count = slots.size();
                int lost = skipped;
                KitAdminService.KitImport kit = new KitAdminService.KitImport(name, slots,
                        Math.max(0, yaml.getLong("cooldown", 0)), BigDecimal.ZERO, permission, -1, oneTime, false, icon);
                String shownPermission = permission == null ? "atrium.kit." + name : permission;
                admin.importKit(kit, overwrite).whenComplete((done, error) -> {
                    if (error != null) {
                        ctx.logger().error("Importing PlayerKits2 kit {} failed", name, error);
                        sender.sendMessage("Kit " + name + ": failed (" + error.getMessage() + ")");
                    } else {
                        String text = "Kit " + name + ": " + (done ? "imported" : "already exists, left alone") + " - "
                                + count + " items" + (lost > 0 ? " (" + lost + " entries without a stored item skipped)" : "")
                                + ", cooldown " + kit.cooldownSeconds() + " s, permission " + shownPermission;
                        ctx.logger().info(text);
                        sender.sendMessage(text);
                    }
                });
            } catch (RuntimeException e) {
                ctx.logger().error("Reading PlayerKits2 kit {} failed", name, e);
                sender.sendMessage("Kit " + name + ": could not be read (" + e.getMessage() + ")");
            }
        }
    }

    private static int number(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
