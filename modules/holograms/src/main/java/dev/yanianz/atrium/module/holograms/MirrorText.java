package dev.yanianz.atrium.module.holograms;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;

/**
 * Converts Atrium lines into the strings an external engine understands. Atrium remains the source of truth;
 * this is only the display projection for one engine. Lines the engine cannot show are turned into visible
 * placeholder text instead of being dropped, so an admin notices them.
 */
final class MirrorText {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR).hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    enum Format {
        /** DecentHolograms: section-colour strings plus its #ICON: line type. */
        LEGACY,
        /** FancyHolograms: MiniMessage strings. */
        MINI_MESSAGE
    }

    private MirrorText() {}

    /**
     * External pages. Engines without pages only receive the first page; the rest stays in Atrium storage.
     */
    static List<List<String>> pages(Hologram hologram, TextRenderer renderer, EngineCapabilities capabilities,
            Format format, String fillerChar, Logger logger) {
        List<List<String>> result = new ArrayList<>();
        for (List<HoloLine> page : hologram.pages()) {
            // The same rainbow and %filler% preparation the native engine applies, so both look alike.
            List<HoloLine> prepared = LineText.preparePage(page, renderer::visibleChars, fillerChar);
            List<String> lines = new ArrayList<>(prepared.size());
            for (HoloLine line : prepared) {
                lines.add(line(line, renderer, capabilities, format, logger));
            }
            result.add(List.copyOf(lines));
            if (!capabilities.pages()) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private static String line(HoloLine line, TextRenderer renderer, EngineCapabilities capabilities, Format format,
            Logger logger) {
        LineContent content = line.content();
        return switch (content) {
            case LineContent.Text text -> serialize(format, renderer.renderLine(text.template(), null));
            case LineContent.Item item -> item(item.material(), capabilities, format);
            case LineContent.SavedItem saved -> item(savedMaterial(saved.base64(), logger), capabilities, format);
            case LineContent.Head head -> item(Material.PLAYER_HEAD.name(), capabilities, format);
            case LineContent.Image image -> serialize(format, Component.text("[image] " + image.kind().key()
                    + " " + image.player()));
            case LineContent.Icon icon -> item(Material.PAPER.name(), capabilities, format);
            case LineContent.Block block -> serialize(format, Component.text("[block] " + block.blockData()));
        };
    }

    private static String item(String material, EngineCapabilities capabilities, Format format) {
        if (capabilities.itemLines() && format == Format.LEGACY) {
            return "#ICON:" + material.toUpperCase(Locale.ROOT);
        }
        return serialize(format, Component.text("[item] " + material.toLowerCase(Locale.ROOT)));
    }

    private static String serialize(Format format, Component component) {
        return format == Format.LEGACY ? LEGACY.serialize(component) : MINI.serialize(component);
    }

    /** The material of a saved #itemstack: line. Only the material is mirrored; NBT stays in Atrium. */
    private static String savedMaterial(String base64, Logger logger) {
        try {
            ItemStack stack = ItemStack.deserializeBytes(Base64.getDecoder().decode(base64));
            Material material = stack.getType();
            return material == null ? Material.BARRIER.name() : material.name();
        } catch (RuntimeException e) {
            if (WARNED.add("saved:" + base64.hashCode())) {
                logger.warn("A saved #itemstack line cannot be read for the external engine; showing a barrier", e);
            }
            return Material.BARRIER.name();
        }
    }
}
