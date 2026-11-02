package dev.yanianz.atrium.module.worth;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Text and number helpers shared by the importers. Pure, so parser tests need no server. */
final class ImportText {
    private static final Pattern LEGACY = Pattern.compile("&(#[0-9a-fA-F]{6}|[0-9a-fk-orA-FK-OR])");

    private ImportText() {}

    /**
     * The plain-text form of a shop display name: legacy colour codes removed, trimmed, {@code null} when
     * nothing is left. Atrium compares custom names as plain text, so the codes must not be imported.
     */
    static @Nullable String cleanName(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String text = LEGACY.matcher(raw.replace('\u00a7', '&')).replaceAll("").trim();
        return text.isEmpty() ? null : text;
    }

    static Optional<BigDecimal> number(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(raw.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static Optional<BigDecimal> number(org.spongepowered.configurate.ConfigurationNode node) {
        return number(node.getString());
    }

    /** Normalises a material name written by another plugin; empty for namespaced custom items. */
    static Optional<String> material(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String name = raw.trim().toLowerCase(Locale.ROOT);
        return name.indexOf(':') >= 0 ? Optional.empty() : Optional.of(name);
    }
}
