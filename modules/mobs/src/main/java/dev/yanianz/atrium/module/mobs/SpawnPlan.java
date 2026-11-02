package dev.yanianz.atrium.module.mobs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Pure limits for /spawnmob. A spec like {@code spider+skeleton} is one stack (bottom first): every
 * stack has the same depth, so the total entity count is {@code stacks * depth}. The depth, the
 * requested amount, the total per command and the free room in the chunk are all bounded.
 */
final class SpawnPlan {
    enum Limit { NONE, TOTAL, CHUNK }

    record Plan(int stacks, int depth, Limit limitedBy) {
        int entities() {
            return stacks * depth;
        }
    }

    private SpawnPlan() {}

    /** Splits a stack spec on {@code +}; empty segments are invalid. Returns lower-case tokens. */
    static List<String> tokens(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Empty entity spec");
        }
        String[] parts = spec.split("\\+", -1);
        List<String> out = new ArrayList<>(parts.length);
        for (String part : parts) {
            String token = part.trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) {
                throw new IllegalArgumentException("Empty entity in spec: " + spec);
            }
            out.add(token);
        }
        return out;
    }

    /** Resolves every token, or returns the first unknown token. */
    static <T> Resolved<T> resolve(List<String> tokens, Function<String, Optional<T>> resolver) {
        List<T> out = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            Optional<T> value = resolver.apply(token);
            if (value.isEmpty()) {
                return new Resolved<>(List.of(), token);
            }
            out.add(value.get());
        }
        return new Resolved<>(List.copyOf(out), null);
    }

    record Resolved<T>(List<T> values, String unknown) {
        boolean ok() {
            return unknown == null;
        }
    }

    /**
     * How many stacks may be spawned. {@code requested} and {@code depth} are already validated against
     * the configured maxima; this caps by the total per command and by the chunk's entity cap.
     */
    static Plan plan(int requested, int depth, int maxTotal, int chunkEntities, int chunkCap) {
        if (requested < 1 || depth < 1) {
            throw new IllegalArgumentException("requested and depth must be positive");
        }
        int byTotal = Math.max(0, maxTotal) / depth;
        int room = Math.max(0, chunkCap - Math.max(0, chunkEntities));
        int byChunk = room / depth;
        int stacks = requested;
        Limit limit = Limit.NONE;
        if (byTotal < stacks) {
            stacks = byTotal;
            limit = Limit.TOTAL;
        }
        if (byChunk < stacks) {
            stacks = byChunk;
            limit = Limit.CHUNK;
        }
        return new Plan(stacks, depth, limit);
    }
}
