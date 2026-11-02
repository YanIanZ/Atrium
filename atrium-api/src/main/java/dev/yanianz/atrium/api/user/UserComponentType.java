package dev.yanianz.atrium.api.user;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A piece of per-player data owned by one module (homes, balance cache, mail, ...).
 *
 * <p>Values should be immutable (records, immutable collections). Change them with
 * {@link AtriumUser#update(UserComponentType, java.util.function.UnaryOperator)}, which swaps the value
 * atomically and marks it dirty so the user service saves it.
 *
 * @param id       unique id, conventionally {@code "<module>:<name>"}
 * @param loader   loads the value from storage; completes with {@code null} when nothing is stored
 * @param saver    persists the value
 * @param defaults value used when the loader returns {@code null}
 */
public record UserComponentType<T>(
        String id,
        Loader<T> loader,
        Saver<T> saver,
        Supplier<T> defaults) {

    public UserComponentType {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(saver, "saver");
        Objects.requireNonNull(defaults, "defaults");
    }

    @FunctionalInterface
    public interface Loader<T> {
        CompletableFuture<T> load(UUID uuid);
    }

    @FunctionalInterface
    public interface Saver<T> {
        CompletableFuture<Void> save(UUID uuid, T value);
    }
}
