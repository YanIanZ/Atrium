package dev.yanianz.atrium.module.worth;

import dev.yanianz.atrium.core.module.ModuleContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Chooses and owns the active price engine. Like the hologram engines, the configured value is authoritative
 * for the next start; {@code /worth engine <id>} switches the live engine only and tells the admin what to
 * write. An external engine that is missing at startup, disabled at runtime or fails a lookup falls back to
 * atrium with one log line; items the external engine does not sell stay unsellable.
 */
final class PriceEngines implements Listener {
    private final ModuleContext ctx;
    private final Supplier<WorthConfig> config;
    private final AtriumPriceEngine atrium;
    private final Map<PriceEngineKind, PriceEngine> engines = new EnumMap<>(PriceEngineKind.class);
    private final Object switchLock = new Object();
    private volatile PriceEngine active;
    private volatile PriceEngineKind activeKind = PriceEngineKind.ATRIUM;
    private volatile PriceEngineKind configured = PriceEngineKind.AUTO;
    private volatile String fallbackReason = "";

    PriceEngines(ModuleContext ctx, Supplier<WorthConfig> config, Supplier<PriceTable> prices,
            Supplier<ItemFactsReader> reader) {
        this.ctx = ctx;
        this.config = config;
        this.atrium = new AtriumPriceEngine(prices, reader);
        this.active = atrium;
        this.engines.put(PriceEngineKind.ATRIUM, atrium);
    }

    // ---- lifecycle ----

    void start() {
        configured = readConfigured();
        fallbackReason = "";
        PriceEngineSelection.Selection selection = selection(configured);
        PriceEngine engine = null;
        if (selection.engine() != PriceEngineKind.ATRIUM) {
            try {
                engine = engine(selection.engine());
            } catch (Throwable error) {
                fallbackReason = PriceEngineSelection.START_FAILED;
                ctx.logger().warn("Price engine '{}' could not start ({}); using atrium", selection.engine().id,
                        error.getMessage());
            }
        }
        if (engine == null) {
            engine = atrium;
            if (selection.fallback()) {
                fallbackReason = selection.reason();
                ctx.logger().warn("Price engine '{}' is unavailable ({}); using atrium", configured.id,
                        selection.reason());
            }
        }
        synchronized (switchLock) {
            active = engine;
            activeKind = engine.kind();
        }
        ctx.logger().info("Price engine: {} (configured: {})", activeKind.id, configured.id);
    }

    /** Re-reads worth.yml. A changed engine applies immediately: no stored data depends on it. */
    void reload() {
        PriceEngineKind wanted = readConfigured();
        PriceEngineSelection.Selection selection = selection(wanted);
        if (wanted == configured && selection.engine() == activeKind && fallbackReason.isEmpty()) {
            return;
        }
        configured = wanted;
        PriceEngine engine;
        String failure = "";
        try {
            engine = selection.engine() == PriceEngineKind.ATRIUM ? atrium : engine(selection.engine());
        } catch (RuntimeException error) {
            engine = atrium;
            failure = PriceEngineSelection.START_FAILED;
            ctx.logger().warn("Price engine '{}' is unavailable ({}); using atrium", selection.engine().id,
                    error.getMessage());
        }
        if (engine == atrium && selection.fallback()) {
            failure = selection.reason();
            ctx.logger().warn("Price engine '{}' is unavailable ({}); using atrium", configured.id,
                    selection.reason());
        }
        synchronized (switchLock) {
            active = engine;
            activeKind = engine.kind();
            fallbackReason = failure;
        }
        ctx.logger().info("Price engine: {} (configured: {})", activeKind.id, configured.id);
    }

    // ---- pricing ----

    /**
     * Unit price of an item of the active engine. Call on the thread that owns the item. A failed external
     * lookup switches to atrium for the rest of this server session and logs once.
     */
    Optional<BigDecimal> unitPrice(ItemStack stack, @Nullable Player player) {
        PriceEngine engine = active;
        if (engine.kind() == PriceEngineKind.ATRIUM) {
            try {
                return engine.unitPrice(stack, player);
            } catch (RuntimeException error) {
                ctx.logger().error("The atrium price engine failed for {}", stack == null ? "null" : stack.getType(),
                        error);
                return Optional.empty();
            }
        }
        Quote quote;
        try {
            quote = quote(engine, atrium, stack, player);
        } catch (RuntimeException error) {
            ctx.logger().error("The atrium price engine failed after falling back", error);
            return Optional.empty();
        }
        if (quote.failure() != null) {
            fallBack(engine.kind(), quote.failure());
        }
        return quote.price();
    }

    /** A price, plus the external engine failure that made {@code fallback} answer instead. */
    record Quote(Optional<BigDecimal> price, @Nullable RuntimeException failure) {}

    /**
     * Asks {@code engine}; on a failure asks {@code fallback} and reports the failure. An empty answer is not
     * a failure: an engine that does not sell the item must stay authoritative.
     */
    static Quote quote(PriceEngine engine, PriceEngine fallback, ItemStack stack, @Nullable Player player) {
        try {
            return new Quote(engine.unitPrice(stack, player), null);
        } catch (RuntimeException error) {
            try {
                return new Quote(fallback.unitPrice(stack, player), error);
            } catch (RuntimeException fallbackError) {
                fallbackError.addSuppressed(error);
                throw fallbackError;
            }
        }
    }

    private void fallBack(PriceEngineKind failed, Throwable error) {
        synchronized (switchLock) {
            if (activeKind != failed) {
                return;
            }
            active = atrium;
            activeKind = PriceEngineKind.ATRIUM;
            fallbackReason = PriceEngineSelection.LOOKUP_FAILED;
        }
        ctx.logger().warn("Price engine '{}' failed ({}); prices now come from atrium", failed.id,
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
    }

    // ---- commands ----

    PriceEngineKind activeKind() {
        return activeKind;
    }

    PriceEngineKind configuredKind() {
        return configured;
    }

    PriceEngineCapabilities capabilities() {
        return active.capabilities();
    }

    /** Empty when the configured engine was used directly. */
    String fallbackReason() {
        return fallbackReason;
    }

    List<String> availableIds() {
        List<String> ids = new ArrayList<>();
        ids.add(PriceEngineKind.ATRIUM.id);
        if (economyShopGuiAvailable()) {
            ids.add(PriceEngineKind.ECONOMYSHOPGUI.id);
        }
        if (shopGuiPlusAvailable()) {
            ids.add(PriceEngineKind.SHOPGUIPLUS.id);
        }
        return List.copyOf(ids);
    }

    /** Switches the live engine. {@link PriceEngineException} carries the reason when it is unavailable. */
    PriceEngineKind switchTo(PriceEngineKind requested) {
        PriceEngineSelection.Selection selection = selection(requested);
        if (selection.fallback()) {
            throw new PriceEngineException(selection.reason());
        }
        PriceEngine engine;
        try {
            engine = selection.engine() == PriceEngineKind.ATRIUM ? atrium : engine(selection.engine());
        } catch (RuntimeException error) {
            throw new PriceEngineException(PriceEngineSelection.START_FAILED + ":" + error.getMessage(), error);
        }
        synchronized (switchLock) {
            active = engine;
            activeKind = engine.kind();
        }
        fallbackReason = "";
        ctx.logger().info("Price engine switched to {} (configured: {})", activeKind.id, configured.id);
        return activeKind;
    }

    // ---- selection ----

    private PriceEngineKind readConfigured() {
        String raw = config.get().priceSource;
        Optional<PriceEngineKind> parsed = PriceEngineKind.parse(raw);
        if (parsed.isEmpty()) {
            ctx.logger().warn("worth.yml: unknown price-source '{}'; using auto", raw);
            return PriceEngineKind.AUTO;
        }
        return parsed.get();
    }

    private PriceEngineSelection.Selection selection(PriceEngineKind requested) {
        return PriceEngineSelection.select(requested, economyShopGuiAvailable(), shopGuiPlusAvailable());
    }

    private boolean economyShopGuiAvailable() {
        for (String name : EconomyShopGuiEngine.PLUGINS) {
            if (pluginEnabled(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean shopGuiPlusAvailable() {
        return pluginEnabled("ShopGUIPlus");
    }

    private static boolean pluginEnabled(String name) {
        return Bukkit.getPluginManager().isPluginEnabled(name);
    }

    private PriceEngine engine(PriceEngineKind kind) {
        PriceEngine existing = engines.get(kind);
        if (existing != null) {
            return existing;
        }
        PriceEngine created = switch (kind) {
            case ATRIUM -> atrium;
            case ECONOMYSHOPGUI -> EconomyShopGuiEngine.create();
            case SHOPGUIPLUS -> ShopGuiPlusEngine.create();
            case AUTO -> throw new PriceEngineException("auto is not a concrete engine");
        };
        engines.put(kind, created);
        return created;
    }

    // ---- external plugin enable/disable ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisable(PluginDisableEvent event) {
        String plugin = event.getPlugin().getName();
        if (Bukkit.isStopping() || !ctx.plugin().isEnabled()
                || !PriceEngineSelection.fallBackOnDisable(activeKind, plugin)) {
            return;
        }
        synchronized (switchLock) {
            active = atrium;
            activeKind = PriceEngineKind.ATRIUM;
            fallbackReason = PriceEngineSelection.PLUGIN_DISABLED;
        }
        ctx.logger().warn("{} was disabled; prices now come from atrium", plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEnable(PluginEnableEvent event) {
        String plugin = event.getPlugin().getName();
        if (Bukkit.isStopping() || !ctx.plugin().isEnabled()) {
            return;
        }
        PriceEngineSelection.Selection selection = selection(configured);
        if (!PriceEngineSelection.returnOnEnable(activeKind, configured, fallbackReason, plugin, selection.engine())) {
            return;
        }
        try {
            PriceEngineKind switched = switchTo(selection.engine());
            ctx.logger().info("{} was enabled; prices now come from {}", plugin, switched.id);
        } catch (RuntimeException error) {
            ctx.logger().warn("Could not switch back to the {} price engine: {}",
                    selection.engine().id.toLowerCase(Locale.ROOT), error.getMessage());
        }
    }
}
