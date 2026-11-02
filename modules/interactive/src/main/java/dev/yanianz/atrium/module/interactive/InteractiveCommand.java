package dev.yanianz.atrium.module.interactive;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * An interactive command: action lines bound to blocks, entities and tagged items. Immutable.
 *
 * @param permission needed to trigger it; empty means everyone
 * @param cooldown   per-player cooldown between triggers; zero for none
 */
record InteractiveCommand(String name, String permission, Duration cooldown, boolean cancelEvent, Trigger trigger,
        List<String> actions) {
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");
    static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9_.\\-]{1,128}");

    /** Which clicks run the command. */
    enum Trigger {
        LEFT, RIGHT, BOTH, PHYSICAL;

        boolean matches(Click click) {
            return switch (this) {
                case LEFT -> click == Click.LEFT;
                case RIGHT -> click == Click.RIGHT;
                case BOTH -> click == Click.LEFT || click == Click.RIGHT;
                case PHYSICAL -> click == Click.PHYSICAL;
            };
        }
    }

    enum Click { LEFT, RIGHT, PHYSICAL }

    enum TargetKind { BLOCK, ENTITY }

    InteractiveCommand {
        actions = List.copyOf(actions);
        cooldown = cooldown == null || cooldown.isNegative() ? Duration.ZERO : cooldown;
        permission = permission == null ? "" : permission;
    }

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    static InteractiveCommand create(String name, boolean cancelEvent) {
        return new InteractiveCommand(name, "", Duration.ZERO, cancelEvent, Trigger.RIGHT, List.of());
    }

    String key() {
        return key(name);
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    InteractiveCommand withActions(List<String> value) {
        return new InteractiveCommand(name, permission, cooldown, cancelEvent, trigger, value);
    }

    InteractiveCommand withAction(String line) {
        List<String> copy = new ArrayList<>(actions);
        copy.add(line);
        return withActions(copy);
    }

    /** Removes the 1-based {@code index}. */
    InteractiveCommand withoutAction(int index) {
        List<String> copy = new ArrayList<>(actions);
        copy.remove(index - 1);
        return withActions(copy);
    }

    InteractiveCommand withPermission(String value) {
        return new InteractiveCommand(name, value, cooldown, cancelEvent, trigger, actions);
    }

    InteractiveCommand withCooldown(Duration value) {
        return new InteractiveCommand(name, permission, value, cancelEvent, trigger, actions);
    }

    InteractiveCommand withCancelEvent(boolean value) {
        return new InteractiveCommand(name, permission, cooldown, value, trigger, actions);
    }

    InteractiveCommand withTrigger(Trigger value) {
        return new InteractiveCommand(name, permission, cooldown, cancelEvent, value, actions);
    }
}
