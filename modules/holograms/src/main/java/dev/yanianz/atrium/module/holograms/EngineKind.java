package dev.yanianz.atrium.module.holograms;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Hologram backends. {@link #AUTO} is a configuration value only: it is resolved to one of the concrete
 * engines at startup (DecentHolograms, then FancyHolograms, then native) and never stored as active engine.
 */
enum EngineKind {
    AUTO("auto"),
    NATIVE("native"),
    DECENTHOLOGRAMS("decentholograms"),
    FANCYHOLOGRAMS("fancyholograms");

    final String id;

    EngineKind(String id) {
        this.id = id;
    }

    boolean concrete() {
        return this != AUTO;
    }

    /** Parses a config or command value, including the common short aliases. */
    static Optional<EngineKind> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String wanted = raw.strip().toLowerCase(Locale.ROOT).replace('-', '_');
        String compact = wanted.replace("_", "");
        for (EngineKind kind : values()) {
            if (kind.id.equals(wanted) || kind.id.equals(compact)
                    || kind.name().toLowerCase(Locale.ROOT).equals(compact)) {
                return Optional.of(kind);
            }
        }
        return switch (wanted) {
            case "decent", "dh" -> Optional.of(DECENTHOLOGRAMS);
            case "fancy", "fh" -> Optional.of(FANCYHOLOGRAMS);
            case "none", "internal", "display" -> Optional.of(NATIVE);
            default -> Optional.empty();
        };
    }

    /** Values accepted by {@code /holo engine migrate}, for tab completion. */
    static List<String> names() {
        return Arrays.stream(values()).map(k -> k.id).toList();
    }

    static List<String> concreteNames() {
        return Arrays.stream(values()).filter(EngineKind::concrete).map(k -> k.id).toList();
    }
}
