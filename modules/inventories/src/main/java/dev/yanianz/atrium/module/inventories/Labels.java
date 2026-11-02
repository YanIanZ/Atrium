package dev.yanianz.atrium.module.inventories;

/**
 * Labels are written by staff but are still untrusted text: they are cleaned here and only ever inserted with
 * {@code Msg.unparsed}. Control characters would break chat and item lore layout, so they become spaces.
 */
final class Labels {
    /** Matches a real Minecraft name; used before a name is embedded in a click command. */
    static final String VALID_NAME = "[A-Za-z0-9_]{1,16}";

    private Labels() {}

    /** @return the cleaned label, or {@code null} when nothing printable remains */
    static String clean(String raw, int maxLength) {
        if (raw == null) {
            return null;
        }
        int max = Math.max(1, maxLength);
        StringBuilder out = new StringBuilder(Math.min(raw.length(), max));
        boolean previousSpace = true;
        for (int index = 0; index < raw.length() && out.length() < max; index++) {
            char value = raw.charAt(index);
            if (Character.isISOControl(value) || value == '\u00a7') {
                value = ' ';
            }
            boolean space = Character.isWhitespace(value) || Character.isSpaceChar(value);
            if (space) {
                if (previousSpace) {
                    continue;
                }
                out.append(' ');
            } else {
                out.append(value);
            }
            previousSpace = space;
        }
        String result = out.toString().strip();
        return result.isEmpty() ? null : result;
    }

    static boolean validName(String name) {
        return name != null && name.matches(VALID_NAME);
    }
}
