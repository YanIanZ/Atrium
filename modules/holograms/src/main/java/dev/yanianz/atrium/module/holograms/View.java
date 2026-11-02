package dev.yanianz.atrium.module.holograms;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;

/**
 * The display entities rendering one page for one audience (everyone, or a single viewer). Only used on the
 * region thread that owns the hologram location. A re-render with the same element structure updates the
 * entities in place (no flicker, no new entity ids); a different structure respawns them.
 */
final class View {
    /** Broken item/block lines are reported once per value, not on every refresh. */
    private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final List<Display> entities = new ArrayList<>();

    /** Live entities of this view (copy). */
    List<Entity> entities() {
        return List.copyOf(entities);
    }

    boolean isEmpty() {
        return entities.isEmpty();
    }

    boolean valid() {
        for (Display display : entities) {
            if (!display.isValid()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Renders {@code layout}. {@code texts} holds one component per text element, in order.
     *
     * @return true when entities were (re)spawned, i.e. viewers must be shown the new ones
     */
    boolean apply(Location base, Layout layout, List<Component> texts, HologramSettings settings, boolean visibleByDefault,
            Consumer<Entity> tag, Logger logger) {
        List<Layout.Element> elements = layout.elements();
        if (!sameStructure(elements, visibleByDefault)) {
            remove();
            int text = 0;
            for (Layout.Element element : elements) {
                Component component = element instanceof Layout.TextElement ? texts.get(text++) : null;
                entities.add(spawn(base, element, component, settings, visibleByDefault, tag, logger));
            }
            return !elements.isEmpty();
        }
        int text = 0;
        for (int i = 0; i < elements.size(); i++) {
            Layout.Element element = elements.get(i);
            Component component = element instanceof Layout.TextElement ? texts.get(text++) : null;
            configure(entities.get(i), element, component, settings, visibleByDefault, logger);
        }
        return false;
    }

    void remove() {
        for (Display display : entities) {
            if (display.isValid()) {
                display.remove();
            }
        }
        entities.clear();
    }

    /**
     * Same element kinds and the same default visibility. A change of default visibility always respawns: a
     * player's show/hide entry inverts the default, so flipping the default of a shown entity would hide it.
     */
    private boolean sameStructure(List<Layout.Element> elements, boolean visibleByDefault) {
        if (elements.size() != entities.size()) {
            return false;
        }
        for (int i = 0; i < elements.size(); i++) {
            Display display = entities.get(i);
            if (!display.isValid() || display.isVisibleByDefault() != visibleByDefault) {
                return false;
            }
            boolean match = switch (elements.get(i)) {
                case Layout.TextElement t -> display instanceof TextDisplay;
                case Layout.ItemElement t -> display instanceof ItemDisplay;
                case Layout.BlockElement t -> display instanceof BlockDisplay;
            };
            if (!match) {
                return false;
            }
        }
        return true;
    }

    private static Display spawn(Location base, Layout.Element element, Component text, HologramSettings settings,
            boolean visibleByDefault, Consumer<Entity> tag, Logger logger) {
        Class<? extends Display> type = switch (element) {
            case Layout.TextElement t -> TextDisplay.class;
            case Layout.ItemElement t -> ItemDisplay.class;
            case Layout.BlockElement t -> BlockDisplay.class;
        };
        // Configure inside the spawn callback: the entity is complete before it is added to the world.
        return base.getWorld().spawn(base, type, display -> {
            display.setPersistent(false);
            tag.accept(display);
            configure(display, element, text, settings, visibleByDefault, logger);
        });
    }

    private static void configure(Display display, Layout.Element element, Component text, HologramSettings settings,
            boolean visibleByDefault, Logger logger) {
        display.setVisibleByDefault(visibleByDefault);
        display.setBillboard(settings.billboard());
        // Display view range is a multiplier of 64 blocks on the client, not a block count.
        display.setViewRange(settings.viewRange() / 64f);
        float scale = settings.scale();
        switch (element) {
            case Layout.TextElement t -> {
                TextDisplay textDisplay = (TextDisplay) display;
                textDisplay.text(text);
                textDisplay.setAlignment(settings.alignment());
                textDisplay.setLineWidth(settings.lineWidth());
                textDisplay.setShadowed(settings.shadow());
                textDisplay.setSeeThrough(settings.seeThrough());
                Integer background = settings.backgroundArgb();
                textDisplay.setDefaultBackground(background == null);
                if (background != null) {
                    textDisplay.setBackgroundColor(Color.fromARGB(background));
                }
                setTransform(display, t.x(), t.y(), t.z(), scale);
            }
            case Layout.ItemElement i -> {
                ItemDisplay itemDisplay = (ItemDisplay) display;
                itemDisplay.setItemStack(item(i.content(), logger));
                itemDisplay.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                setTransform(display, i.x(), i.y(), i.z(), (float) i.width());
            }
            case Layout.BlockElement b -> {
                ((BlockDisplay) display).setBlock(block(b.content(), logger));
                setTransform(display, b.x(), b.y(), b.z(), (float) b.width());
            }
        }
    }

    private static void setTransform(Display display, float x, float y, float z, float scale) {
        Transformation wanted = new Transformation(new Vector3f(x, y, z), new Quaternionf(),
                new Vector3f(scale, scale, scale), new Quaternionf());
        if (!wanted.equals(display.getTransformation())) {
            display.setTransformation(wanted);
        }
    }

    static ItemStack item(LineContent content, Logger logger) {
        try {
            if (content instanceof LineContent.Item item) {
                Material material = Material.matchMaterial(item.material());
                if (material != null && material.isItem() && !material.isAir()) {
                    return new ItemStack(material);
                }
                if (WARNED.add("item:" + item.material())) {
                    logger.warn("Hologram item line: unknown item '{}'; showing a barrier", item.material());
                }
            } else if (content instanceof LineContent.SavedItem saved) {
                return ItemStack.deserializeBytes(Base64.getDecoder().decode(saved.base64()));
            }
        } catch (RuntimeException e) {
            if (WARNED.add("stack:" + content.hashCode())) {
                logger.warn("Hologram item line could not be read; showing a barrier", e);
            }
        }
        return new ItemStack(Material.BARRIER);
    }

    static BlockData block(LineContent.Block content, Logger logger) {
        try {
            return Bukkit.createBlockData(content.blockData());
        } catch (IllegalArgumentException e) {
            if (WARNED.add("block:" + content.blockData())) {
                logger.warn("Hologram block line: invalid block data '{}'; showing a barrier", content.blockData(), e);
            }
            return Material.BARRIER.createBlockData();
        }
    }
}
