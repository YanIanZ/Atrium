package dev.yanianz.atrium.core.user;

/**
 * Converts a user component value to bytes and back, so the value can be exported (for example into a
 * SourbyCraft profile snapshot) and imported again without going through the module's SQL tables.
 * Implementations must be pure and fast: they run on the player's tick thread.
 */
public interface ComponentCodec<T> {
    byte[] encode(T value) throws Exception;

    T decode(byte[] data) throws Exception;
}
