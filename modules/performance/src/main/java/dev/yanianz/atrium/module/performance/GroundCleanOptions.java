package dev.yanianz.atrium.module.performance;

import java.util.Locale;

/** Parsed options of {@code /groundclean [world] [-s] [-now]}. */
record GroundCleanOptions(String world, boolean silent, boolean immediate) {
    static GroundCleanOptions parse(String raw) {
        String world = "";
        boolean silent = false;
        boolean immediate = false;
        if (raw != null) {
            for (String token : raw.trim().split("\\s+")) {
                if (token.isBlank()) {
                    continue;
                }
                String lower = token.toLowerCase(Locale.ROOT);
                switch (lower) {
                    case "-s", "--silent" -> silent = true;
                    case "-now", "--now", "-f", "--force" -> immediate = true;
                    default -> {
                        if (lower.startsWith("-")) {
                            throw new IllegalArgumentException("Unknown flag: " + token);
                        }
                        String name = token;
                        if (lower.startsWith("world:")) {
                            name = token.substring("world:".length());
                            if (name.isBlank()) {
                                throw new IllegalArgumentException("world: needs a world name");
                            }
                        }
                        world = world.isEmpty() ? name : mergeWorlds(world, name);
                    }
                }
            }
        }
        return new GroundCleanOptions(world, silent, immediate);
    }

    private static String mergeWorlds(String current, String next) {
        // A world name may contain spaces, so a greedy string can produce several bare words.
        // Joining them is friendlier than rejecting "/groundclean My World" outright.
        return current + " " + next;
    }
}
