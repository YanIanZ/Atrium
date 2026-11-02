package dev.yanianz.atrium.module.holograms;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;

/**
 * The display entities rendering one page for one audience (everyone, or a single viewer). Only used on the
 * region thread that owns the hologram location. A re-render with the same element structure updates the
 * entities in place (no flicker, no new entity ids); a different structure respawns them.
 *
 * <p>Animation: only the transformation is touched while a fade runs, so nothing is respawned and the click box
 * stays in place.
 */
final class View {
    /** Broken item/block lines are reported once per value, not on every refresh. */
    private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Heads are cheap to build but not free; the texture comes from the cached profile, never from the network. */
    private static final Map<String, ItemStack> HEADS = new ConcurrentHashMap<>();
    private static final int MAX_CACHED_HEADS = 256;

    private final Displays displays;
    private final List<Display> entities = new ArrayList<>();
    private Layout lastLayout;
    private HologramSettings lastSettings;

    View(Displays displays) {
        this.displays = displays;
    }

    /**
     * What a personal copy needs from its viewer, captured on the player's own thread so the region thread never
     * touches another player's inventory or profile.
     */
    record ViewerItems(ItemStack hand, com.destroystokyo.paper.profile.PlayerProfile profile, String name) {}

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
     * @param fade      scale multiplier of a running fade (1 = full size)
     * @param viewer    what a personal copy needs from its viewer; null for the shared copy
     * @return true when entities were (re)spawned, i.e. viewers must be shown the new ones
     */
    boolean apply(Location base, Layout layout, List<Component> texts, HologramSettings settings,
            boolean visibleByDefault, float fade, Consumer<Entity> tag, Logger logger,
            @Nullable ViewerItems viewer) {
        lastLayout = layout;
        lastSettings = settings;
        List<Layout.Element> elements = layout.elements();
        if (!sameStructure(elements, visibleByDefault)) {
            remove();
            int text = 0;
            for (Layout.Element element : elements) {
                Component component = element instanceof Layout.TextElement ? texts.get(text++) : null;
                entities.add(spawn(base, element, component, settings, visibleByDefault, fade, tag, logger, viewer));
            }
            return !elements.isEmpty();
        }
        int text = 0;
        for (int i = 0; i < elements.size(); i++) {
            Layout.Element element = elements.get(i);
            Component component = element instanceof Layout.TextElement ? texts.get(text++) : null;
            configure(entities.get(i), element, component, settings, visibleByDefault, fade, logger, viewer);
        }
        return false;
    }

    /** Region: only the transformation changes while a fade runs. */
    void animate(float fade) {
        if (lastLayout == null || lastSettings == null) {
            return;
        }
        List<Layout.Element> elements = lastLayout.elements();
        if (elements.size() != entities.size()) {
            return;
        }
        for (int i = 0; i < elements.size(); i++) {
            Display display = entities.get(i);
            if (display.isValid()) {
                setTransform(display, elements.get(i), lastSettings, fade);
            }
        }
    }

    /**
     * Region: turns every entity of this view. Only a FIXED or HORIZONTAL billboard uses the entity rotation; a
     * billboard that turns towards the viewer would override it, so setting it there has no visible effect.
     */
    void rotate(float yaw, float pitch) {
        for (Display display : entities) {
            if (display.isValid()) {
                display.setRotation(yaw, pitch);
            }
        }
    }

    void remove() {
        for (Display display : entities) {
            if (display.isValid()) {
                display.remove();
            }
        }
        entities.clear();
        lastLayout = null;
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

    private Display spawn(Location base, Layout.Element element, Component text, HologramSettings settings,
            boolean visibleByDefault, float fade, Consumer<Entity> tag, Logger logger, @Nullable ViewerItems viewer) {
        Class<? extends Display> type = switch (element) {
            case Layout.TextElement t -> TextDisplay.class;
            case Layout.ItemElement t -> ItemDisplay.class;
            case Layout.BlockElement t -> BlockDisplay.class;
        };
        // Configure inside the spawn callback: the entity is complete before it is added to the world.
        return base.getWorld().spawn(base, type, display -> {
            display.setPersistent(false);
            tag.accept(display);
            configure(display, element, text, settings, visibleByDefault, fade, logger, viewer);
        });
    }

    private void configure(Display display, Layout.Element element, Component text, HologramSettings settings,
            boolean visibleByDefault, float fade, Logger logger, @Nullable ViewerItems viewer) {
        display.setVisibleByDefault(visibleByDefault);
        display.setBillboard(settings.billboard());
        // Display view range is a multiplier of 64 blocks on the client, not a block count.
        display.setViewRange(settings.viewRange() / 64f);
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
            }
            case Layout.ItemElement i -> {
                ItemDisplay itemDisplay = (ItemDisplay) display;
                itemDisplay.setItemStack(item(i.content(), viewer, logger));
                itemDisplay.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            }
            case Layout.BlockElement b -> {
                ((BlockDisplay) display).setBlock(block(b.content(), logger));
            }
        }
        setTransform(display, element, settings, fade);
    }

    private static void setTransform(Display display, Layout.Element element, HologramSettings settings, float fade) {
        float scale = settings.scale() * Math.max(Animation.MIN_FADE, Math.min(1f, fade));
        Transformation wanted = switch (element) {
            case Layout.TextElement t -> new Transformation(new Vector3f(t.x(), t.y(), t.z()), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf());
            case Layout.ItemElement i -> new Transformation(new Vector3f(i.x(), i.y(), i.z()), new Quaternionf(),
                    new Vector3f((float) i.width() * fade, (float) i.height() * fade, (float) i.width() * fade),
                    new Quaternionf());
            case Layout.BlockElement b -> new Transformation(new Vector3f(b.x(), b.y(), b.z()), new Quaternionf(),
                    new Vector3f((float) b.width() * fade, (float) b.height() * fade, (float) b.width() * fade),
                    new Quaternionf());
        };
        if (!wanted.equals(display.getTransformation())) {
            display.setTransformation(wanted);
        }
    }

    private ItemStack item(LineContent content, @Nullable ViewerItems viewer, Logger logger) {
        try {
            return switch (content) {
                case LineContent.Item item -> material(item.material(), logger);
                case LineContent.SavedItem saved -> ItemStack.deserializeBytes(Base64.getDecoder().decode(saved.base64()));
                case LineContent.Head head -> head(head.player(), viewer, logger);
                case LineContent.Icon icon -> icon(icon, viewer, logger);
                default -> new ItemStack(Material.BARRIER);
            };
        } catch (RuntimeException e) {
            if (WARNED.add("stack:" + content.hashCode())) {
                logger.warn("Hologram item line could not be read; showing a barrier", e);
            }
            return new ItemStack(Material.BARRIER);
        }
    }

    private static ItemStack material(String material, Logger logger) {
        Material found = Material.matchMaterial(material);
        if (found != null && found.isItem() && !found.isAir()) {
            return new ItemStack(found);
        }
        if (WARNED.add("item:" + material)) {
            logger.warn("Hologram item line: unknown item '{}'; showing a barrier", material);
        }
        return new ItemStack(Material.BARRIER);
    }

    private ItemStack icon(LineContent.Icon icon, @Nullable ViewerItems viewer, Logger logger) {
        if (icon.kind() == LineContent.IconKind.CUSTOM_MODEL_DATA) {
            Material found = displays.iconMaterial(icon.customModelData());
            if (found == null) {
                if (WARNED.add("cmd:" + icon.customModelData())) {
                    logger.warn("Hologram icon: custom model data {} has no material in holograms.yml icons; "
                            + "showing paper", icon.customModelData());
                }
                found = Material.PAPER;
            }
            return new ItemStack(found);
        }
        if (viewer == null || viewer.hand().isEmpty()) {
            // The shared copy has no viewer: an empty hand is shown as paper so the line keeps its slot.
            return new ItemStack(Material.PAPER);
        }
        return viewer.hand().clone();
    }

    /**
     * A player head. Textures are only taken from a profile the server already knows (an online player or the
     * cached usercache entry); the profile is never completed over the network on the region thread, so the head
     * may be plain for a player who never joined this server.
     */
    private static ItemStack head(String player, @Nullable ViewerItems viewer, Logger logger) {
        String name = player.isEmpty() ? (viewer == null ? "" : viewer.name()) : player;
        if (name.isEmpty()) {
            return new ItemStack(Material.PLAYER_HEAD);
        }
        String key = name.toLowerCase(java.util.Locale.ROOT);
        ItemStack cached = HEADS.get(key);
        if (cached != null) {
            return cached.clone();
        }
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        try {
            com.destroystokyo.paper.profile.PlayerProfile profile;
            if (player.isEmpty() && viewer != null) {
                profile = viewer.profile();
            } else {
                Player online = Bukkit.getPlayerExact(name);
                profile = online != null ? online.getPlayerProfile() : Bukkit.createProfile(name);
            }
            if (profile != null && stack.getItemMeta() instanceof SkullMeta meta) {
                // The profile is only used as it is; completing it would perform a web request on the region thread.
                meta.setPlayerProfile(profile);
                stack.setItemMeta(meta);
            }
        } catch (RuntimeException e) {
            logger.warn("Hologram head line: cannot build the head of '{}'; showing a plain head", name, e);
        }
        if (HEADS.size() >= MAX_CACHED_HEADS) {
            HEADS.clear();
        }
        HEADS.put(key, stack);
        return stack.clone();
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
