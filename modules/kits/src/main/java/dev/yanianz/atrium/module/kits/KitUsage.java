package dev.yanianz.atrium.module.kits;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Immutable per-player kit usage: kit name to last claim time and number of claims. */
public record KitUsage(Map<String, Claim> claims) {
    public static final KitUsage EMPTY = new KitUsage(Map.of());

    public KitUsage {
        claims = Map.copyOf(claims);
    }

    public record Claim(long lastMillis, int count) {}

    public enum Status { AVAILABLE, COOLDOWN, ALREADY_CLAIMED, MAX_USES }

    public record Availability(Status status, Duration remaining) {
        public boolean available() {
            return status == Status.AVAILABLE;
        }
    }

    public @Nullable Claim claim(String kit) {
        return claims.get(kit);
    }

    /** A copy with one more claim of {@code kit} at {@code now}. */
    public KitUsage claimed(String kit, long now) {
        Map<String, Claim> copy = new HashMap<>(claims);
        Claim previous = copy.get(kit);
        int count = previous == null ? 1 : (previous.count() == Integer.MAX_VALUE ? Integer.MAX_VALUE : previous.count() + 1);
        copy.put(kit, new Claim(now, count));
        return new KitUsage(copy);
    }

    /** A copy where {@code kit} has exactly {@code claim} (null removes it); used to undo a reservation. */
    public KitUsage with(String kit, @Nullable Claim claim) {
        Map<String, Claim> copy = new HashMap<>(claims);
        if (claim == null) copy.remove(kit);
        else copy.put(kit, claim);
        return new KitUsage(copy);
    }

    /** Clears the cooldown timestamp of {@code kit}, or of every kit when {@code kit} is null. */
    public KitUsage resetCooldown(@Nullable String kit) {
        if (kit == null) {
            Map<String, Claim> copy = new HashMap<>(claims.size());
            claims.forEach((name, c) -> copy.put(name, new Claim(0, c.count())));
            return new KitUsage(copy);
        }
        Claim current = claims.get(kit);
        if (current == null) return this;
        Map<String, Claim> copy = new HashMap<>(claims);
        copy.put(kit, new Claim(0, current.count()));
        return new KitUsage(copy);
    }

    /** Clears the use counter of {@code kit}, or of every kit when {@code kit} is null, keeping the cooldown. */
    public KitUsage resetUses(@Nullable String kit) {
        if (kit == null) {
            Map<String, Claim> copy = new HashMap<>(claims.size());
            claims.forEach((name, c) -> copy.put(name, new Claim(c.lastMillis(), 0)));
            return new KitUsage(copy);
        }
        Claim current = claims.get(kit);
        if (current == null) return this;
        Map<String, Claim> copy = new HashMap<>(claims);
        copy.put(kit, new Claim(current.lastMillis(), 0));
        return new KitUsage(copy);
    }

    /**
     * Whether the kit can be claimed now. A last-claim time in the future (clock moved backwards) is treated as
     * "now", so the full cooldown applies instead of an absurdly long or negative one.
     *
     * @param maxUses -1 = unlimited, 0 = disabled, positive = maximum number of claims
     */
    public static Availability check(@Nullable Claim claim, long cooldownSeconds, boolean oneTime, int maxUses,
            long nowMillis, boolean bypassCooldown, boolean bypassOneTime, boolean bypassMaxUses) {
        int used = claim == null ? 0 : Math.max(0, claim.count());
        if (oneTime && used > 0 && !bypassOneTime) {
            return new Availability(Status.ALREADY_CLAIMED, Duration.ZERO);
        }
        if (!bypassMaxUses && maxUses >= 0 && used >= maxUses) {
            return new Availability(Status.MAX_USES, Duration.ZERO);
        }
        long last = claim == null ? 0 : claim.lastMillis();
        if (cooldownSeconds <= 0 || bypassCooldown || last <= 0) {
            return new Availability(Status.AVAILABLE, Duration.ZERO);
        }
        long lastClamped = Math.min(last, nowMillis);
        long cooldownMillis = cooldownSeconds > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : cooldownSeconds * 1000;
        long end = lastClamped > Long.MAX_VALUE - cooldownMillis ? Long.MAX_VALUE : lastClamped + cooldownMillis;
        long remaining = end - nowMillis;
        if (remaining <= 0) {
            return new Availability(Status.AVAILABLE, Duration.ZERO);
        }
        // Round up to whole seconds so "0s left" is never shown while still on cooldown.
        long seconds = remaining / 1000 + (remaining % 1000 == 0 ? 0 : 1);
        return new Availability(Status.COOLDOWN, Duration.ofSeconds(seconds));
    }

    /** Convenience overload for kits without an explicit maximum. */
    public static Availability check(@Nullable Claim claim, long cooldownSeconds, boolean oneTime, long nowMillis,
            boolean bypassCooldown, boolean bypassOneTime) {
        return check(claim, cooldownSeconds, oneTime, -1, nowMillis, bypassCooldown, bypassOneTime, false);
    }
}
