package dev.yanianz.atrium.module.portals;

import dev.yanianz.atrium.api.portals.PortalUseEvent.TargetType;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Where a portal sends players. Stored as text: {@code ""} (none), {@code warp:<name>},
 * {@code server:<name>} or {@code location:<StoredLocation#encode()>}. The location encoding uses
 * {@link Double#toString}, so it does not depend on the JVM locale.
 */
record PortalTarget(TargetType type, String value) {
    static final PortalTarget NONE = new PortalTarget(TargetType.NONE, "");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    PortalTarget {
        Objects.requireNonNull(type, "type");
        value = value == null ? "" : value;
    }

    /**
     * Parses what an admin typed: {@code none}, {@code location}, {@code warp:<name>} or {@code server:<name>}.
     * {@code here} supplies the admin's position for {@code location}. Empty when the text is not valid.
     */
    static Optional<PortalTarget> parse(String spec, Supplier<Optional<StoredLocation>> here) {
        String text = spec == null ? "" : spec.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.equals("none")) {
            return Optional.of(NONE);
        }
        if (lower.equals("location") || lower.equals("here")) {
            return here.get().map(l -> new PortalTarget(TargetType.LOCATION, l.encode()));
        }
        if (lower.startsWith("warp:")) {
            return named(TargetType.WARP, text.substring(5));
        }
        if (lower.startsWith("server:")) {
            return named(TargetType.SERVER, text.substring(7));
        }
        return Optional.empty();
    }

    private static Optional<PortalTarget> named(TargetType type, String name) {
        return NAME.matcher(name).matches() ? Optional.of(new PortalTarget(type, name)) : Optional.empty();
    }

    String encode() {
        return switch (type) {
            case NONE -> "";
            case WARP -> "warp:" + value;
            case SERVER -> "server:" + value;
            case LOCATION -> "location:" + value;
        };
    }

    /** Decodes {@link #encode()}; unknown or broken text decodes to {@link #NONE}. */
    static PortalTarget decode(String text) {
        if (text == null || text.isEmpty()) {
            return NONE;
        }
        int colon = text.indexOf(':');
        if (colon < 0) {
            return NONE;
        }
        String kind = text.substring(0, colon);
        String rest = text.substring(colon + 1);
        return switch (kind) {
            case "warp" -> named(TargetType.WARP, rest).orElse(NONE);
            case "server" -> named(TargetType.SERVER, rest).orElse(NONE);
            case "location" -> StoredLocation.decode(rest).isPresent() ? new PortalTarget(TargetType.LOCATION, rest) : NONE;
            default -> NONE;
        };
    }

    Optional<StoredLocation> location() {
        return type == TargetType.LOCATION ? StoredLocation.decode(value) : Optional.empty();
    }

    /** Human-readable, locale-independent description for /portal info. */
    String describe() {
        return switch (type) {
            case NONE -> "none";
            case WARP -> "warp " + value;
            case SERVER -> "server " + value;
            case LOCATION -> location()
                    .map(l -> String.format(Locale.ROOT, "%s %.1f %.1f %.1f", l.world(), l.x(), l.y(), l.z()))
                    .orElse("invalid location");
        };
    }
}
