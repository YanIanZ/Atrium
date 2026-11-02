package dev.yanianz.atrium.module.actions;

import dev.yanianz.atrium.api.economy.TransactionResult;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jspecify.annotations.Nullable;

/**
 * Everything a running sequence needs from the server, for one player (or console). Methods without a future
 * are called on the owner's thread (the player's thread, or the global thread for the console). Abstracted so
 * {@link SequenceRunner} can be tested without a server.
 */
interface ActionHost {
    /** True when a player context exists. Player conditions fail when false. */
    boolean hasPlayer();

    /** {player}, {uuid}, {world}, {x}, {y}, {z}; empty without a player. */
    Map<String, String> builtIns();

    boolean hasPermission(String node);

    @Nullable String world();

    /** Balance, empty when unknown (no economy, error). */
    CompletableFuture<Optional<BigDecimal>> balance();

    /** Time left on the cooldown ({@link Duration#ZERO} when not running); empty when unknown. */
    Optional<Duration> cooldownRemaining(String key);

    /** Atomic check-and-start. ZERO = started; positive = time left; empty = unknown. */
    Optional<Duration> tryCooldown(String key, Duration duration);

    void clearCooldown(String key);

    /** Uniform random number in [0, 100). */
    double randomPercent();

    /** Dispatches as console on the global region thread. Completes with the dispatch result. */
    CompletableFuture<Boolean> console(String command);

    /** Runs a command as the player, with the player's own permissions. */
    boolean performCommand(String command);

    void message(String template, Map<String, String> vars);

    void actionBar(String template, Map<String, String> vars);

    void title(String title, String subtitle, Map<String, String> vars);

    void sound(ActionParser.SoundSpec sound);

    /** Sends {@code actions.<key>} from the language files. */
    void notify(String key, TagResolver... resolvers);

    String formatMoney(BigDecimal amount);

    /** Fails exceptionally when no economy is available. */
    CompletableFuture<TransactionResult> withdraw(BigDecimal amount);

    CompletableFuture<TransactionResult> deposit(BigDecimal amount);

    /** Completes with true when the player arrived. */
    CompletableFuture<Boolean> teleport(StoredLocation target);

    Optional<StoredLocation> warp(String name);

    /** Runs {@code task} on the owner's thread; {@code gone} when the player left first. */
    void resume(Runnable task, Runnable gone);

    /** Runs {@code task} on the owner's thread after {@code ticks}; {@code gone} when the player left first. */
    void later(long ticks, Runnable task, Runnable gone);

    void warn(String message);

    void error(String message, Throwable error);
}
