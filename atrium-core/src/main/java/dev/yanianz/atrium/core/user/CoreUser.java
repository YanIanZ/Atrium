package dev.yanianz.atrium.core.user;

import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.api.user.UserComponentType;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

final class CoreUser implements AtriumUser {
    private final UUID uuid;
    private final Instant firstJoin;
    private volatile String name;
    private volatile Instant lastSeen;
    private volatile Locale locale;
    private volatile String lastAddress;
    final Map<UserComponentType<?>, Object> components = new ConcurrentHashMap<>();
    final Set<UserComponentType<?>> dirty = ConcurrentHashMap.newKeySet();

    CoreUser(UUID uuid, String name, Instant firstJoin, Instant lastSeen, Locale locale) {
        this.uuid = uuid;
        this.name = name;
        this.firstJoin = firstJoin;
        this.lastSeen = lastSeen;
        this.locale = locale;
    }

    @Override
    public UUID uuid() {
        return uuid;
    }

    @Override
    public String name() {
        return name;
    }

    void name(String name) {
        this.name = name;
    }

    @Override
    public Instant firstJoin() {
        return firstJoin;
    }

    @Override
    public Instant lastSeen() {
        return isOnline() ? Instant.now() : lastSeen;
    }

    void lastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    @Override
    public Locale locale() {
        return player().map(Player::locale).orElse(locale);
    }

    void locale(Locale locale) {
        this.locale = locale;
    }

    @Override
    public Optional<String> lastAddress() {
        return Optional.ofNullable(lastAddress);
    }

    String lastAddressOrNull() {
        return lastAddress;
    }

    void lastAddress(String address) {
        this.lastAddress = address;
    }

    @Override
    public Optional<Player> player() {
        return Optional.ofNullable(Bukkit.getPlayer(uuid));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(UserComponentType<T> type) {
        Object value = components.get(type);
        if (value == null) {
            throw new IllegalStateException("Component " + type.id() + " is not loaded for " + uuid);
        }
        return (T) value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T update(UserComponentType<T> type, UnaryOperator<T> change) {
        Object updated = components.compute(type, (k, old) -> {
            if (old == null) {
                throw new IllegalStateException("Component " + type.id() + " is not loaded for " + uuid);
            }
            T next = change.apply((T) old);
            if (next == null) {
                throw new IllegalArgumentException("Component " + type.id() + " cannot be set to null");
            }
            return next;
        });
        dirty.add(type);
        return (T) updated;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CoreUser other && other.uuid.equals(uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }
}
