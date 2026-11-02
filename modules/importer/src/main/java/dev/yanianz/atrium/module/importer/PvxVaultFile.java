package dev.yanianz.atrium.module.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Reads the keys of one PlayerVaultsX vault file ({@code newvaults/<uuid>.yml}).
 *
 * <p>Current PlayerVaultsX writes {@code vault1}, {@code vault2}, ...; older builds wrote the bare number.
 * Both are accepted. The value is the base64 vault payload decoded by {@link PvxVaultCodec}; a map value is
 * reported separately because it is a YAML item map and not the base64 format this importer reads.
 */
final class PvxVaultFile {
    /** One vault entry of the file. {@code nonText} is a value that is not a base64 string. */
    record Entry(int number, String data, boolean nonText) {}

    private PvxVaultFile() {}

    /** The vault entries, ordered by number; keys that are not a vault number are skipped. */
    static List<Entry> parse(ConfigurationNode root) {
        if (root == null || root.virtual() || !root.isMap()) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (var child : root.childrenMap().entrySet()) {
            int number = number(String.valueOf(child.getKey()));
            if (number < 1) {
                continue;
            }
            ConfigurationNode value = child.getValue();
            if (value.isMap() || value.isList()) {
                entries.add(new Entry(number, null, true));
            } else {
                String data = value.getString();
                entries.add(new Entry(number, data == null ? "" : data.strip(), false));
            }
        }
        entries.sort(java.util.Comparator.comparingInt(Entry::number));
        return List.copyOf(entries);
    }

    /** {@code vault3} and {@code 3} are vault 3; anything else is 0. */
    static int number(String key) {
        if (key == null || key.isBlank()) {
            return 0;
        }
        String text = key.strip().toLowerCase(Locale.ROOT);
        if (text.startsWith("vault")) {
            text = text.substring("vault".length());
        }
        try {
            int number = Integer.parseInt(text);
            return number >= 1 && number <= PvxVaultCodec.MAX_SLOTS ? number : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
