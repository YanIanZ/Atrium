package dev.yanianz.atrium.module.actions;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** What an action line does. Every executor is written {@code name: argument}. */
enum Executor {
    CONSOLE(true, "console"),
    PLAYER(true, "player"),
    MSG(false, "msg", "message"),
    ACTIONBAR(false, "actionbar"),
    TITLE(false, "title"),
    SOUND(true, "sound"),
    DELAY(false, "delay", "wait"),
    TAKE_MONEY(true, "take-money"),
    GIVE_MONEY(true, "give-money"),
    TELEPORT(true, "teleport", "tp"),
    WARP(true, "warp"),
    STOP(false, "stop");

    /** Executor names that are refused on purpose: running commands with operator rights is never offered. */
    static final Set<String> FORBIDDEN = Set.of("op", "asop", "as-op", "fake-op", "operator", "sudo");

    private static final Map<String, Executor> BY_NAME = new HashMap<>();

    static {
        for (Executor executor : values()) {
            for (String name : executor.names) {
                BY_NAME.put(name, executor);
            }
        }
    }

    /** True when the argument is validated as safe text after variable substitution (commands, numbers, keys). */
    final boolean strictVariables;
    final List<String> names;

    Executor(boolean strictVariables, String... names) {
        this.strictVariables = strictVariables;
        this.names = List.of(names);
    }

    static Executor byName(String name) {
        return BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }
}
