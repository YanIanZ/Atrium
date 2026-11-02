package dev.yanianz.atrium.module.signs;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.DyeColor;

/** Immutable copy of both sides of a sign and its wood colour. */
public record SignClipboard(List<Component> front, List<Component> back, boolean frontGlowing,
        boolean backGlowing, DyeColor color) {
    public SignClipboard {
        front = List.copyOf(front);
        back = List.copyOf(back);
    }
}
