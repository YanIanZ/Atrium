package dev.yanianz.atrium.module.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.economy.TransactionResult;
import dev.yanianz.atrium.api.playtime.PlaytimeService;
import dev.yanianz.atrium.api.ranks.RankService;
import dev.yanianz.atrium.api.service.ServiceRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PlaceholderResolutionTest {
    private static final UUID ID = UUID.randomUUID();

    /** Minimal type-keyed registry. */
    static final class Registry implements ServiceRegistry {
        private final Map<Class<?>, Object> services = new HashMap<>();

        @Override
        public <T> void register(Class<T> type, T implementation) {
            services.put(type, implementation);
        }

        @Override
        public <T> void unregister(Class<T> type, T implementation) {
            services.remove(type, implementation);
        }

        @Override
        public <T> Optional<T> get(Class<T> type) {
            return Optional.ofNullable(type.cast(services.get(type)));
        }
    }

    private static DisplayPlaceholders placeholders(Registry registry, AtomicLong clock) {
        DisplayPlaceholders p = new DisplayPlaceholders(registry, LoggerFactory.getLogger("test"),
                Duration.ofSeconds(30), clock::get);
        p.missingText("n/a");
        return p;
    }

    @Test
    void missingServicesRenderMissingText() {
        DisplayPlaceholders p = placeholders(new Registry(), new AtomicLong());
        assertEquals("n/a", p.balance(ID));
        assertEquals("n/a", p.rank(ID));
        assertEquals("n/a", p.playtime(ID));
    }

    @Test
    void availableServicesAreUsed() {
        Registry registry = new Registry();
        registry.register(EconomyService.class, new FakeEconomy(new BigDecimal("12.5")));
        registry.register(RankService.class, new FakeRanks(CompletableFuture.completedFuture(Optional.of("elite"))));
        registry.register(PlaytimeService.class, new FakePlaytime(3_700));
        DisplayPlaceholders p = placeholders(registry, new AtomicLong());
        assertEquals("$12.5", p.balance(ID));
        assertEquals("elite", p.rank(ID));
        assertNotEquals("n/a", p.playtime(ID));
    }

    @Test
    void uncachedBalanceIsLoadedAsynchronously() {
        Registry registry = new Registry();
        CompletableFuture<BigDecimal> pending = new CompletableFuture<>();
        FakeEconomy economy = new FakeEconomy(null);
        economy.balance = pending;
        registry.register(EconomyService.class, economy);
        DisplayPlaceholders p = placeholders(registry, new AtomicLong());
        assertEquals("n/a", p.balance(ID), "still loading");
        pending.complete(new BigDecimal("3"));
        assertEquals("$3", p.balance(ID));
    }

    @Test
    void failedLookupKeepsLastValueAndRetriesAfterRefresh() {
        Registry registry = new Registry();
        FakeRanks ranks = new FakeRanks(CompletableFuture.completedFuture(Optional.of("member")));
        registry.register(RankService.class, ranks);
        AtomicLong clock = new AtomicLong();
        DisplayPlaceholders p = placeholders(registry, clock);
        assertEquals("member", p.rank(ID));
        ranks.next = CompletableFuture.failedFuture(new IllegalStateException("db down"));
        clock.addAndGet(Duration.ofSeconds(31).toNanos());
        assertEquals("member", p.rank(ID), "a failure keeps the previous value");
        assertEquals(2, ranks.calls.get());
        assertEquals("member", p.rank(ID));
        assertEquals(2, ranks.calls.get(), "no retry before the refresh interval");
    }

    @Test
    void emptyRankAndServiceThatThrowsRenderMissing() {
        Registry registry = new Registry();
        registry.register(RankService.class, new FakeRanks(CompletableFuture.completedFuture(Optional.empty())));
        registry.register(PlaytimeService.class, new PlaytimeService() {
            @Override
            public CompletableFuture<Long> totalPlaytime(UUID player) {
                throw new IllegalStateException("broken");
            }

            @Override
            public long sessionTime(UUID player) {
                return 0;
            }
        });
        DisplayPlaceholders p = placeholders(registry, new AtomicLong());
        assertEquals("n/a", p.rank(ID));
        assertEquals("n/a", p.playtime(ID));
    }

    @Test
    void valuesAreInsertedLiterally() {
        PlaceholderValues values = new PlaceholderValues("<red>Bob", null, "3", "20", "world", "42", "20.0",
                "$1", "1h", "<click:run_command:/op Bob>x");
        Component c = MiniMessage.miniMessage().deserialize("<player>|<displayname>|<online>/<max>|<rank>",
                values.resolver());
        assertEquals("<red>Bob|<red>Bob|3/20|<click:run_command:/op Bob>x",
                PlainTextComponentSerializer.plainText().serialize(c));
    }

    @Test
    void tpsFormatting() {
        assertEquals("20.0", DisplayPlaceholders.formatTps(21.37));
        assertEquals("19.5", DisplayPlaceholders.formatTps(19.47));
        assertEquals("0.0", DisplayPlaceholders.formatTps(Double.NaN));
    }

    @Test
    void papiTokensBecomeTagsOnce() {
        PapiTokens.Tokenized t = PapiTokens.tokenize("<gray>%player_name% has %vault_eco_balance% (%player_name%) 50% off");
        assertEquals("<gray><papi_0> has <papi_1> (<papi_0>) 50% off", t.template());
        assertEquals(List.of("player_name", "vault_eco_balance"), t.tokens());
        assertEquals("no tokens", PapiTokens.tokenize("no tokens").template());
        assertTrue(PapiTokens.tokenize("<gradient:#fff:#000>%a b%</gradient>").tokens().isEmpty());
    }

    @Test
    void targetingMatchesWorldAndPermission() {
        Targeting t = new Targeting(List.of("World_Nether"), "perm.x");
        assertTrue(t.matches("world_nether", "perm.x"::equals));
        assertEquals(false, t.matches("world", "perm.x"::equals));
        assertEquals(false, t.matches("world_nether", p -> false));
        assertTrue(Targeting.EVERYONE.matches("anything", p -> false));
    }

    private static final class FakeEconomy implements EconomyService {
        private final BigDecimal cached;
        CompletableFuture<BigDecimal> balance = CompletableFuture.completedFuture(BigDecimal.ZERO);

        FakeEconomy(BigDecimal cached) {
            this.cached = cached;
        }

        @Override
        public CompletableFuture<BigDecimal> balance(UUID account) {
            return balance;
        }

        @Override
        public BigDecimal cachedBalance(UUID account) {
            return cached;
        }

        @Override
        public CompletableFuture<TransactionResult> deposit(UUID account, BigDecimal amount, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<TransactionResult> withdraw(UUID account, BigDecimal amount, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<TransactionResult> set(UUID account, BigDecimal amount, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<TransactionResult> transfer(UUID from, UUID to, BigDecimal amount, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String format(BigDecimal amount) {
            return "$" + amount.toPlainString();
        }

        @Override
        public String currencyNameSingular() {
            return "dollar";
        }

        @Override
        public String currencyNamePlural() {
            return "dollars";
        }
    }

    private static final class FakeRanks implements RankService {
        CompletableFuture<Optional<String>> next;
        final AtomicInteger calls = new AtomicInteger();

        FakeRanks(CompletableFuture<Optional<String>> next) {
            this.next = next;
        }

        @Override
        public List<String> ranks() {
            return List.of("member", "elite");
        }

        @Override
        public String startingRank() {
            return "member";
        }

        @Override
        public CompletableFuture<Optional<String>> currentRank(UUID player) {
            calls.incrementAndGet();
            return next;
        }
    }

    private record FakePlaytime(long total) implements PlaytimeService {
        @Override
        public CompletableFuture<Long> totalPlaytime(UUID player) {
            return CompletableFuture.completedFuture(total);
        }

        @Override
        public long sessionTime(UUID player) {
            return 0;
        }
    }
}
