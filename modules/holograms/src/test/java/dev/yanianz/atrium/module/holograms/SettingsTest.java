package dev.yanianz.atrium.module.holograms;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @Test void roundTripsUnderACommaDecimalLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.of("id", "ID"));
            HologramSettings s = HologramSettings.builtInDefaults()
                    .with(Setting.SCALE, "1,5")
                    .with(Setting.LINE_SPACING, "0.125")
                    .with(Setting.INTERACTION_WIDTH, "2.25")
                    .with(Setting.BILLBOARD, "vertical")
                    .with(Setting.ALIGNMENT, "left")
                    .with(Setting.BACKGROUND, "#112233")
                    .with(Setting.PERMISSION, "Atrium.Holo.VIP")
                    .with(Setting.PER_VIEWER, "on");
            String encoded = s.encode();
            assertFalse(encoded.contains("1,5"));
            List<String> problems = new ArrayList<>();
            HologramSettings back = HologramSettings.decode(encoded, HologramSettings.builtInDefaults(),
                    (k, p) -> problems.add(k));
            assertEquals(s, back);
            assertTrue(problems.isEmpty());
            assertEquals(1.5f, back.scale());
            assertEquals(2.25f, back.interactionWidth());
            assertEquals(Display.Billboard.VERTICAL, back.billboard());
            assertEquals(TextDisplay.TextAlignment.LEFT, back.alignment());
            assertEquals(0xFF112233, back.backgroundArgb());
            assertEquals("atrium.holo.vip", back.permission());
            assertEquals(Setting.PerViewer.ON, back.perViewer());
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test void decodeKeepsBaseValuesForUnknownOrInvalidEntries() {
        HologramSettings base = HologramSettings.builtInDefaults().with(Setting.SCALE, "2");
        List<String> problems = new ArrayList<>();
        HologramSettings decoded = HologramSettings.decode("scale=abc\nnope=1\nshadow=true\ngarbage\n", base,
                (k, p) -> problems.add(k));
        assertEquals(2f, decoded.scale());
        assertTrue(decoded.shadow());
        assertEquals(List.of("scale", "nope", "garbage"), problems);
    }

    @Test void missingKeysTakeTheBase() {
        HologramSettings base = HologramSettings.builtInDefaults().with(Setting.VIEW_RANGE, "16");
        assertEquals(16f, HologramSettings.decode("shadow=true", base, (k, p) -> fail(k)).viewRange());
    }

    @Test void rejectsOutOfRangeAndMalformedValues() {
        HologramSettings s = HologramSettings.builtInDefaults();
        assertThrows(IllegalArgumentException.class, () -> s.with(Setting.SCALE, "0"));
        assertThrows(IllegalArgumentException.class, () -> s.with(Setting.SCALE, "NaN"));
        assertThrows(IllegalArgumentException.class, () -> s.with(Setting.VIEW_RANGE, "1000"));
        assertThrows(IllegalArgumentException.class, () -> s.with(Setting.UPDATE_INTERVAL, "-1"));
        assertThrows(IllegalArgumentException.class, () -> s.with(Setting.BILLBOARD, "sideways"));
        assertThrows(IllegalArgumentException.class, () -> s.with(Setting.SHADOW, "maybe"));
        assertThrows(IllegalArgumentException.class, () -> s.with(Setting.PERMISSION, "bad perm"));
        assertThrows(IllegalArgumentException.class, () -> s.with(Setting.BACKGROUND, "red"));
    }

    @Test void backgroundForms() {
        assertNull(HologramSettings.builtInDefaults().backgroundArgb());
        assertEquals(0, HologramSettings.builtInDefaults().with(Setting.BACKGROUND, "none").backgroundArgb());
        assertEquals(0x40000000, HologramSettings.builtInDefaults().with(Setting.BACKGROUND, "#40000000").backgroundArgb());
    }

    @Test void permissionNoneClears() {
        HologramSettings s = HologramSettings.builtInDefaults().with(Setting.PERMISSION, "a.b").with(Setting.PERMISSION, "none");
        assertEquals("", s.permission());
    }

    @Test void keysAcceptUnderscores() {
        assertEquals(Setting.SEE_THROUGH, Setting.byKey("see_through").orElseThrow());
        assertEquals(Setting.VIEW_RANGE, Setting.byKey("View-Range").orElseThrow());
        assertTrue(Setting.byKey("colour").isEmpty());
    }

    @Test void configDefaultsBecomeSettings() {
        HologramsConfig config = new HologramsConfig();
        config.defaults.scale = 0.5f;
        config.defaults.billboard = "fixed";
        config.defaults.perViewer = "bogus";
        List<String> problems = new ArrayList<>();
        HologramSettings s = config.defaultSettings((k, p) -> problems.add(k));
        assertEquals(0.5f, s.scale());
        assertEquals(Display.Billboard.FIXED, s.billboard());
        assertEquals(Setting.PerViewer.AUTO, s.perViewer());
        assertEquals(List.of("per-viewer"), problems);
    }
}
