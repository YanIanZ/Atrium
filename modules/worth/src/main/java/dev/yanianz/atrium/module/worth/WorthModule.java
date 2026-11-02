package dev.yanianz.atrium.module.worth;

import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.worth.WorthService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Item prices, /worth, /sell, /sellgui, /setworth and the sale log.
 *
 * <p>Prices come from a selectable engine: Atrium's own table, EconomyShopGUI or ShopGUIPlus
 * ({@code /worth engine}). {@code /worth import} copies another plugin's sell prices into Atrium's table so
 * the other plugin can be removed.
 */
public final class WorthModule implements AtriumModule, Listener, WorthService {
    static final String HEADER = """
            Atrium item prices. Edit, then run /atrium reload (or use /setworth in game).
            items:
              <material>: <price>          plain entry; matches ONLY the vanilla item (no custom model data,
                                           item model, custom name, lore, enchantments or plugin data)
              <id>:                        detailed entry
                material: <material>       defaults to the id
                price: <price>
                custom-model-data: [1001]  exact tokens: numbers, s:<string>, b:<true|false>, c:#rrggbb
                item-model: <namespace:key>
                name: <exact plain-text custom name>
                enchantments: {sharpness: 5}   exact enchantment set
                accept: [lore, enchantments, name, custom-model-data, item-model, components]
                                           attributes that may have ANY value (else the item must not have them)
            enchantment-values:
              <enchantment>: <price per level>  added for entries that accept enchantments
            A price of 0 makes an item unsellable.""";

    private ModuleContext ctx;
    private ConfigHandle<WorthConfig> config;
    private WorthStore store;
    private ItemReturns returns;
    private SellService sell;
    private SellGui gui;
    private PriceEngines engines;
    private PriceImportService imports;
    private volatile PriceMaterials materials = PriceMaterials.empty();
    private volatile PriceTable prices = PriceTable.empty();
    private volatile ItemFactsReader reader = new ItemFactsReader(java.util.List.of());
    private final Object fileLock = new Object();

    @Override
    public String id() {
        return "worth";
    }

    @Override
    public String description() {
        return "Item prices, selling and the sell GUI";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("economy");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.config = ctx.config(WorthConfig.class, WorthConfig::new);
        ctx.migrate(WorthStore.MIGRATIONS);
        EconomyService economy = ctx.services().require(EconomyService.class);
        store = new WorthStore(ctx.database());
        returns = new ItemReturns(ctx, store);
        // The item registry is read here, on the server thread; imports and reloads only use this snapshot.
        materials = PriceMaterials.snapshot();
        loadPrices();
        engines = new PriceEngines(ctx, config::get, () -> prices, () -> reader);
        engines.start();
        imports = new PriceImportService(ctx, this);
        sell = new SellService(ctx, () -> reader, config::get, economy, store, returns, () -> engines);
        gui = new SellGui(ctx, sell, returns, config::get);
        ctx.listen(this);
        ctx.listen(gui);
        ctx.listen(engines);
        ctx.service(WorthService.class, this);

        ctx.permission("atrium.worth", PermissionDefault.TRUE, "Look up item prices");
        ctx.permission("atrium.sell", PermissionDefault.TRUE, "Sell items with /sell");
        ctx.permission("atrium.sellgui", PermissionDefault.TRUE, "Open the sell GUI");
        ctx.permission("atrium.selllog", PermissionDefault.TRUE, "View own recent sales");
        ctx.permission("atrium.setworth", PermissionDefault.OP, "Set the price of the held item");
        ctx.permission("atrium.worth.engine", PermissionDefault.OP, "Show or switch the price engine");
        ctx.permission("atrium.worth.import", PermissionDefault.OP, "Import sell prices from another plugin");
        ctx.permission("atrium.sell.limit.unlimited", PermissionDefault.FALSE, "No daily sell limit");
        new WorthCommands(ctx, this, sell, gui, store, engines, imports).register();

        // Online players (module enabled at runtime) may have pending returns.
        for (var user : ctx.users().online()) {
            user.player().ifPresent(returns::deliver);
        }
    }

    @Override
    public void disable() {
        if (gui != null) {
            gui.shutdown();
        }
    }

    @Override
    public void reload() throws Exception {
        loadPrices();
        if (engines != null) {
            engines.reload();
        }
    }

    PriceTable prices() {
        return prices;
    }

    PriceMaterials materials() {
        return materials;
    }

    PriceEngines engines() {
        return engines;
    }

    PriceImportService imports() {
        return imports;
    }

    /** Active-engine price of an item of this stack. Call on the thread that owns the item. */
    Optional<BigDecimal> price(ItemStack item, @Nullable Player player) {
        return engines.unitPrice(item, player);
    }

    ItemFactsReader reader() {
        return reader;
    }

    WorthConfig config() {
        return config.get();
    }

    Path worthFile() {
        return ctx.dataDirectory().resolve("worth.yml");
    }

    static YamlConfigurationLoader loader(Path path) {
        return YamlConfigurationLoader.builder()
                .path(path)
                .nodeStyle(NodeStyle.BLOCK)
                .indent(2)
                .defaultOptions(o -> o.header(HEADER))
                .build();
    }

    private void loadPrices() throws IOException {
        WorthConfig cfg = config.get();
        PriceTable.ContainerMode mode;
        try {
            mode = PriceTable.ContainerMode.valueOf(cfg.containerMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.logger().warn("Unknown worth container-mode '{}'; using EMPTY_ONLY", cfg.containerMode);
            mode = PriceTable.ContainerMode.EMPTY_ONLY;
        }
        PriceTable.Options options = new PriceTable.Options(cfg.devalueByDurability, cfg.requireFullDurability,
                cfg.blockCustomNames, cfg.unsellableLore, mode, Math.max(0, Math.min(10, cfg.priceScale)));
        synchronized (fileLock) {
            Path file = worthFile();
            if (!Files.exists(file)) {
                try (InputStream in = WorthModule.class.getResourceAsStream("/worth/default-worth.yml")) {
                    if (in == null) {
                        throw new IOException("Bundled default-worth.yml is missing");
                    }
                    Files.createDirectories(file.getParent());
                    Files.copy(in, file);
                }
            }
            CommentedConfigurationNode root = loader(file).load();
            WorthFile.Parsed parsed = WorthFile.parse(root, materials::isItem);
            parsed.warnings().forEach(w -> ctx.logger().warn("worth.yml {}", w));
            reader = new ItemFactsReader(cfg.ignoredComponents);
            prices = new PriceTable(parsed.entries(), parsed.enchantmentValues(), options);
            ctx.logger().info("Loaded {} item prices", parsed.entries().size());
        }
    }

    static boolean isItemMaterial(String name) {
        Material material = Material.matchMaterial(name);
        return material != null && material.isItem() && !material.isAir();
    }    /** Adds or replaces an entry and writes worth.yml. Blocking file I/O: call off the game threads. */
    void saveEntry(PriceEntry entry) throws ConfigurateException {
        saveEntries(List.of(entry));
    }

    /** Writes several entries with one file pass; the price table is replaced once. Off the game threads. */
    void saveEntries(List<PriceEntry> entries) throws ConfigurateException {
        synchronized (fileLock) {
            YamlConfigurationLoader loader = loader(worthFile());
            CommentedConfigurationNode root = loader.load();
            for (PriceEntry entry : entries) {
                WorthFile.write(root, entry);
            }
            loader.save(root);
            // Merge in memory: re-reading the file would need the item registry, which is server-thread only.
            List<PriceEntry> merged = new ArrayList<>(prices.entries());
            for (PriceEntry entry : entries) {
                int index = -1;
                for (int i = 0; i < merged.size(); i++) {
                    if (merged.get(i).id().equals(entry.id())) {
                        index = i;
                        break;
                    }
                }
                if (index >= 0) {
                    merged.set(index, entry);
                } else {
                    merged.add(entry);
                }
            }
            prices = new PriceTable(merged, prices.enchantmentValues(), prices.options());
        }
    }

    @Override
    public Optional<BigDecimal> unitPrice(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return engines.unitPrice(item, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        returns.deliver(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        var player = event.getPlayer();
        ctx.track(ctx.scheduler().entityLater(player, () -> returns.deliver(player), null, 2));
    }
}
