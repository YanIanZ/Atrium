package dev.yanianz.atrium.module.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.api.user.UserService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * The additive ignore write behind {@link dev.yanianz.atrium.api.chat.IgnoreAdminService}: a second
 * import must not change anything, the player's own UUID is dropped, and the write is only reported as
 * done after the store accepted it.
 */
class ChatIgnoreAdminServiceTest {
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    private static final UserComponentType<ChatUser> COMPONENT =
            new UserComponentType<>("chat:user", uuid -> CompletableFuture.completedFuture(null),
                    (uuid, value) -> CompletableFuture.completedFuture(null), () -> ChatUser.DEFAULT);

    /** Minimal in-memory user with a real atomic component map. */
    private static final class FakeUser implements AtriumUser {
        private final UUID uuid;
        private final Map<UserComponentType<?>, Object> components = new HashMap<>();

        FakeUser(UUID uuid) {
            this.uuid = uuid;
            components.put(COMPONENT, ChatUser.DEFAULT);
        }

        @Override
        public UUID uuid() {
            return uuid;
        }

        @Override
        public String name() {
            return "u" + uuid.toString().substring(0, 8);
        }

        @Override
        public Instant firstJoin() {
            return Instant.EPOCH;
        }

        @Override
        public Instant lastSeen() {
            return Instant.EPOCH;
        }

        @Override
        public Locale locale() {
            return Locale.ROOT;
        }

        @Override
        public Optional<Player> player() {
            return Optional.empty();
        }

        @Override
        public Optional<String> lastAddress() {
            return Optional.empty();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T get(UserComponentType<T> type) {
            return (T) components.get(type);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T update(UserComponentType<T> type, java.util.function.UnaryOperator<T> change) {
            Object next = change.apply((T) components.get(type));
            components.put(type, next);
            return (T) next;
        }
    }

    private static final class FakeUsers implements UserService {
        final Map<UUID, FakeUser> users = new HashMap<>();
        int saves;

        @Override
        public AtriumUser user(Player player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AtriumUser> cached(UUID uuid) {
            return Optional.ofNullable(users.get(uuid));
        }

        @Override
        public CompletableFuture<Optional<AtriumUser>> load(UUID uuid) {
            return CompletableFuture.completedFuture(Optional.ofNullable(users.get(uuid)));
        }

        @Override
        public CompletableFuture<Optional<AtriumUser>> loadByName(String name) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public Collection<AtriumUser> online() {
            return List.of();
        }

        @Override
        public <T> void registerComponent(UserComponentType<T> type) {
            // Not needed by the writer.
        }

        @Override
        public CompletableFuture<Void> saveNow(UUID uuid) {
            saves++;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Optional<dev.yanianz.atrium.api.user.PlayerRef>> resolveAccount(String name,
                boolean includeNeverJoined) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<List<dev.yanianz.atrium.api.user.PlayerRef>> accountsByAddress(String address) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public Collection<dev.yanianz.atrium.api.user.PlayerRef> knownAccounts(int limit) {
            return List.of();
        }

        @Override
        public Collection<String> knownNames(String prefix, int limit) {
            return List.of();
        }
    }

    private static Set<UUID> ignored(FakeUser user) {
        return user.get(COMPONENT).ignored();
    }

    @Test
    void importIsAdditiveAndIdempotent() {
        FakeUsers users = new FakeUsers();
        FakeUser alice = new FakeUser(ALICE);
        users.users.put(ALICE, alice);
        ChatIgnoreAdminService service = new ChatIgnoreAdminService(users, COMPONENT);

        assertEquals(2, service.importIgnores(ALICE, List.of(BOB, CAROL)).join());
        assertEquals(Set.of(BOB, CAROL), ignored(alice));

        assertEquals(0, service.importIgnores(ALICE, List.of(BOB, CAROL)).join(), "a second run adds nothing");
        assertEquals(Set.of(BOB, CAROL), ignored(alice));
        assertEquals(2, users.saves, "the value is stored once per call");

        // A player who adds an ignore after the import keeps it on the next run.
        alice.update(COMPONENT, current -> current.withIgnored(BOB, false));
        assertEquals(1, service.importIgnores(ALICE, List.of(BOB, CAROL)).join(), "only the missing entry is added");
        assertEquals(Set.of(BOB, CAROL), ignored(alice));
        assertEquals(0, service.importIgnores(ALICE, List.of(BOB, CAROL)).join(), "then nothing is left to add");
    }

    @Test
    void selfAndNullEntriesAreDropped() {
        FakeUsers users = new FakeUsers();
        FakeUser alice = new FakeUser(ALICE);
        users.users.put(ALICE, alice);
        ChatIgnoreAdminService service = new ChatIgnoreAdminService(users, COMPONENT);

        List<UUID> entries = new ArrayList<>();
        entries.add(ALICE);
        entries.add(null);
        entries.add(BOB);
        assertEquals(1, service.importIgnores(ALICE, entries).join());
        assertEquals(Set.of(BOB), ignored(alice));
        assertEquals(0, service.importIgnores(ALICE, List.of(ALICE, BOB)).join());
    }

    @Test
    void unknownPlayerFailsInsteadOfReportingSuccess() {
        FakeUsers users = new FakeUsers();
        ChatIgnoreAdminService service = new ChatIgnoreAdminService(users, COMPONENT);

        CompletionException failure = assertThrows(CompletionException.class,
                () -> service.importIgnores(ALICE, List.of(BOB)).join());
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertEquals(0, users.saves);
    }

    @Test
    void emptyListCompletesWithoutTouchingStorage() {
        FakeUsers users = new FakeUsers();
        users.users.put(ALICE, new FakeUser(ALICE));
        ChatIgnoreAdminService service = new ChatIgnoreAdminService(users, COMPONENT);

        assertEquals(0, service.importIgnores(ALICE, List.of()).join());
        assertEquals(0, users.saves);
        assertTrue(ChatIgnoreAdminService.sanitize(ALICE, null).isEmpty());
        assertFalse(ChatIgnoreAdminService.sanitize(ALICE, List.of(ALICE)).contains(ALICE));
    }
}
