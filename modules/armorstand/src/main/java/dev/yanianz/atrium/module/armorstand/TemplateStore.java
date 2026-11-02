package dev.yanianz.atrium.module.armorstand;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Named placement templates in {@code plugins/Atrium/modules/armorstand/templates.yml}.
 *
 * <p>Blocking file I/O: the file is read once at enable and written on an I/O thread after an edit. A
 * template that cannot be read is skipped with a log line instead of breaking the whole file. Equipment is
 * stored as base64 of the server's own item byte form, so a template never depends on YAML item syntax.
 */
final class TemplateStore {
    static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    private final java.nio.file.Path file;
    private final Logger logger;
    private final Map<String, StandTemplate> templates = new LinkedHashMap<>();

    TemplateStore(java.nio.file.Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    synchronized Map<String, StandTemplate> all() {
        return Map.copyOf(templates);
    }

    synchronized Optional<StandTemplate> get(String name) {
        return Optional.ofNullable(templates.get(name == null ? "" : name.toLowerCase(Locale.ROOT)));
    }

    synchronized List<String> names() {
        return new ArrayList<>(templates.keySet());
    }

    /** Reads the file; a missing file is not an error. Call from enable or an I/O thread. */
    synchronized void load() throws IOException {
        templates.clear();
        if (!java.nio.file.Files.isRegularFile(file)) {
            return;
        }
        ConfigurationNode root;
        try {
            root = YamlConfigurationLoader.builder().path(file).build().load();
        } catch (ConfigurateException e) {
            throw new IOException("cannot read " + file.getFileName() + ": " + e.getMessage(), e);
        }
        ConfigurationNode section = root.node("templates");
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : section.childrenMap().entrySet()) {
            String name = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            if (!validName(name)) {
                logger.warn("Armor stand template '{}' skipped: names use a-z, 0-9, _ and -", name);
                continue;
            }
            try {
                StandTemplate template = read(name, entry.getValue());
                templates.put(name, template);
            } catch (RuntimeException e) {
                logger.warn("Armor stand template '{}' skipped: {}", name, e.getMessage());
            }
        }
    }

    private StandTemplate read(String name, ConfigurationNode node) {
        String poseText = node.node("pose").getString("");
        Pose pose = poseText == null || poseText.isBlank() ? Pose.DEFAULT : Pose.decode(poseText);
        Set<Toggle> toggles = EnumSet.noneOf(Toggle.class);
        List<String> toggleKeys;
        try {
            toggleKeys = node.node("toggles").getList(String.class, List.of());
        } catch (org.spongepowered.configurate.serialize.SerializationException e) {
            throw new IllegalArgumentException("toggles must be a list of names", e);
        }
        for (String key : toggleKeys) {
            for (Toggle toggle : Toggle.values()) {
                if (toggle.key().equalsIgnoreCase(key)) {
                    toggles.add(toggle);
                }
            }
        }
        String customName = node.node("name").getString(null);
        Map<EquipmentSlot, ItemStack> items = new EnumMap<>(EquipmentSlot.class);
        ConfigurationNode equipment = node.node("equipment");
        for (EquipmentSlot slot : StandTemplate.SLOTS) {
            String encoded = equipment.node(slot.name().toLowerCase(Locale.ROOT)).getString("");
            if (encoded == null || encoded.isBlank()) {
                continue;
            }
            try {
                ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded.strip()));
                if (!item.isEmpty()) {
                    items.put(slot, item);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("slot " + slot.name().toLowerCase(Locale.ROOT)
                        + " is not a readable item: " + e.getMessage());
            }
        }
        return new StandTemplate(name, pose, toggles, customName, items);
    }

    /** Adds or replaces a template and writes the file. Blocking. */
    synchronized void put(StandTemplate template) throws IOException {
        templates.put(template.name(), template);
        save();
    }

    /** Removes a template; returns false when the name was unknown. Blocking. */
    synchronized boolean delete(String name) throws IOException {
        if (templates.remove(name) == null) {
            return false;
        }
        save();
        return true;
    }

    synchronized private void save() throws IOException {
        java.nio.file.Files.createDirectories(file.getParent());
        ConfigurationNode root;
        try {
            root = YamlConfigurationLoader.builder().path(file).build().createNode();
            root.node("templates").set(null);
            ConfigurationNode section = root.node("templates");
            for (StandTemplate template : templates.values()) {
                ConfigurationNode node = section.node(template.name());
                node.node("pose").set(template.pose().encode());
                node.node("toggles").setList(String.class, template.toggleKeys().stream().toList());
                if (template.customName() != null) {
                    node.node("name").set(template.customName());
                }
                for (Map.Entry<EquipmentSlot, ItemStack> entry : template.equipment().entrySet()) {
                    node.node("equipment", entry.getKey().name().toLowerCase(Locale.ROOT))
                            .set(Base64.getEncoder().encodeToString(entry.getValue().serializeAsBytes()));
                }
            }
            YamlConfigurationLoader.builder().path(file).build().save(root);
        } catch (ConfigurateException e) {
            throw new IOException("cannot write " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }
}
