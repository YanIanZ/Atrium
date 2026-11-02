package dev.yanianz.atrium.module.sourbycraft;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Parsing of the {@code profile-scoped} and {@code profile-missing-state} options. Pure. */
final class ProfileScopes {
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    /** Data that is account-wide by design; listing it is a configuration mistake. */
    static final Set<String> ACCOUNT_WIDE = Set.of("economy", "balance", "money", "nick", "mail", "moderation",
            "playtime", "chat", "particles");

    enum MissingState { EMPTY, KEEP }

    private ProfileScopes() {}

    /** Normalised, de-duplicated ids in config order. Invalid and account-wide entries are reported and skipped. */
    static List<String> parse(List<String> raw, Consumer<String> warn) {
        Set<String> result = new LinkedHashSet<>();
        if (raw == null) {
            return List.of();
        }
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String id = entry.trim().toLowerCase(Locale.ROOT);
            if (!ID.matcher(id).matches()) {
                warn.accept("profile-scoped: '" + entry + "' is not a valid id ([a-z0-9_-], 1-32 characters); ignored");
                continue;
            }
            if (ACCOUNT_WIDE.contains(id)) {
                warn.accept("profile-scoped: '" + id + "' is account-wide and cannot follow profiles"
                        + (id.equals("economy") || id.equals("balance") || id.equals("money")
                        ? " (money per profile is the SourbyCraft purse's job)" : "") + "; ignored");
                continue;
            }
            if (!result.add(id)) {
                warn.accept("profile-scoped: '" + id + "' is listed twice");
            }
        }
        return List.copyOf(new ArrayList<>(result));
    }

    static MissingState missingState(String raw, Consumer<String> warn) {
        if (raw != null) {
            try {
                return MissingState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // reported below
            }
        }
        warn.accept("profile-missing-state: '" + raw + "' is not EMPTY or KEEP; using EMPTY");
        return MissingState.EMPTY;
    }
}
