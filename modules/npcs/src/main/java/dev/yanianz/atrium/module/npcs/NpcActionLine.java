package dev.yanianz.atrium.module.npcs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One click-action line. Atrium understands three short directives itself and hands every other line to
 * the {@code actions} module (ActionService):
 *
 * <pre>
 *   open dialog &lt;name&gt;   open an admin-defined Paper dialog
 *   warp &lt;name&gt;          teleport to a warp
 *   server &lt;name&gt;        send the player to another proxy server
 *   console: say hi      any regular ActionService line
 * </pre>
 *
 * <p>Lines are admin templates, but a target name is still validated: it is inserted into a command or a
 * service call, so a space or a colon must never reach those layers.
 */
record NpcActionLine(Type type, String value, String raw) {
    enum Type {
        ACTION,
        DIALOG,
        WARP,
        SERVER
    }

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

    NpcActionLine {
        if (raw == null) {
            raw = "";
        }
    }

    static NpcActionLine parse(String raw) {
        String line = raw == null ? "" : raw.trim();
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.startsWith("open dialog ")) {
            return new NpcActionLine(Type.DIALOG, line.substring("open dialog ".length()).trim(), line);
        }
        if (lower.equals("open dialog")) {
            return new NpcActionLine(Type.DIALOG, "", line);
        }
        if (lower.startsWith("warp ")) {
            return new NpcActionLine(Type.WARP, line.substring("warp ".length()).trim(), line);
        }
        if (lower.equals("warp")) {
            return new NpcActionLine(Type.WARP, "", line);
        }
        if (lower.startsWith("server ")) {
            return new NpcActionLine(Type.SERVER, line.substring("server ".length()).trim(), line);
        }
        if (lower.equals("server")) {
            return new NpcActionLine(Type.SERVER, "", line);
        }
        return new NpcActionLine(Type.ACTION, line, line);
    }

    boolean directive() {
        return type != Type.ACTION;
    }

    /** A blank line is ignored; otherwise the first problem with this line, or empty when it is valid. */
    Optional<String> problem() {
        if (raw.isBlank()) {
            return Optional.of("empty line");
        }
        if (raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) {
            return Optional.of("a line cannot contain a line break");
        }
        if (!directive()) {
            return Optional.empty();
        }
        if (value.isBlank()) {
            return Optional.of("missing a name after " + type.name().toLowerCase(Locale.ROOT));
        }
        if (!SAFE_NAME.matcher(value).matches()) {
            return Optional.of("name may only contain letters, digits, _ . and - (1-64 characters)");
        }
        return Optional.empty();
    }

    /** Lines to hand to ActionService in one batch, in their original order. */
    static List<String> actionLines(List<String> raws) {
        List<String> out = new ArrayList<>();
        if (raws == null) {
            return out;
        }
        for (String raw : raws) {
            NpcActionLine line = parse(raw);
            if (!line.directive() && !line.raw().isBlank()) {
                out.add(line.raw());
            }
        }
        return out;
    }
}
