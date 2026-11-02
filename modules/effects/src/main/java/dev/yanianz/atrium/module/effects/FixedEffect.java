package dev.yanianz.atrium.module.effects;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.jspecify.annotations.Nullable;

/** A persistent particle arrangement placed by an admin. Immutable. */
public record FixedEffect(
        String name,
        StoredLocation location,
        Shape shape,
        Particle particle,
        @Nullable Color colour,
        double radius,
        double height,
        int points,
        double speed,
        int intervalTicks) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    public FixedEffect {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(particle, "particle");
        radius = Math.max(Shapes.MIN_RADIUS, Math.min(Shapes.MAX_RADIUS, radius));
        height = Math.max(0.0, Math.min(Shapes.MAX_HEIGHT, height));
        points = Shapes.clamp(points, Shapes.MIN_POINTS, Shapes.MAX_POINTS);
        speed = Double.isFinite(speed) ? Math.max(0.0, Math.min(90.0, speed)) : 0.0;
        intervalTicks = Math.max(1, Math.min(40, intervalTicks));
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public String key() {
        return key(name);
    }

    /** Data object for the particle, or {@code null} when the stored colour does not satisfy the type. */
    public ParticleData.Check data() {
        ParticleData.Check check = ParticleData.check(particle, colour, colour, 1.0);
        return check.valid() ? check : null;
    }

    public FixedEffect moved(StoredLocation newLocation) {
        return new FixedEffect(name, newLocation, shape, particle, colour, radius, height, points, speed, intervalTicks);
    }

    public FixedEffect withShape(Shape newShape) {
        return new FixedEffect(name, location, newShape, particle, colour, radius, height, points, speed, intervalTicks);
    }

    public FixedEffect withParticle(Particle newParticle, @Nullable Color newColour) {
        return new FixedEffect(name, location, shape, newParticle, newColour, radius, height, points, speed, intervalTicks);
    }

    public FixedEffect withColour(@Nullable Color newColour) {
        return new FixedEffect(name, location, shape, particle, newColour, radius, height, points, speed, intervalTicks);
    }

    public FixedEffect withRadius(double newRadius) {
        return new FixedEffect(name, location, shape, particle, colour, newRadius, height, points, speed, intervalTicks);
    }

    public FixedEffect withHeight(double newHeight) {
        return new FixedEffect(name, location, shape, particle, colour, radius, newHeight, points, speed, intervalTicks);
    }

    public FixedEffect withPoints(int newPoints) {
        return new FixedEffect(name, location, shape, particle, colour, radius, height, newPoints, speed, intervalTicks);
    }

    public FixedEffect withSpeed(double newSpeed) {
        return new FixedEffect(name, location, shape, particle, colour, radius, height, points, newSpeed, intervalTicks);
    }

    public FixedEffect withInterval(int newInterval) {
        return new FixedEffect(name, location, shape, particle, colour, radius, height, points, speed, newInterval);
    }

    /** Summary tokens used by the list and edit feedback, e.g. {@code spiral END_ROD #ff8800}. */
    public String detail() {
        StringBuilder text = new StringBuilder(shape.id()).append(' ').append(particle.name());
        if (colour != null) {
            text.append(' ').append(ParticleData.formatColour(colour));
        }
        text.append(" r=").append(format(radius));
        if (height > 0) {
            text.append(" h=").append(format(height));
        }
        return text.toString();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
