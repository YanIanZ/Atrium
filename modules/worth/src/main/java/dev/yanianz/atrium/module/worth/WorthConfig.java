package dev.yanianz.atrium.module.worth;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/worth.yml (prices themselves live in plugins/Atrium/data/worth/worth.yml). */
@ConfigSerializable
public class WorthConfig {
    @Comment("Price engine: auto, atrium, economyshopgui or shopguiplus.\n"
            + "auto = EconomyShopGUI if enabled, else ShopGUIPlus if enabled, else atrium.\n"
            + "/worth engine shows the active engine and what it supports.")
    public String priceSource = "auto";

    @Comment("Lower a tool's price by its lost durability (price * remaining / max durability).")
    public boolean devalueByDurability = true;

    @Comment("Refuse to buy damaged items at all (takes precedence over devalue-by-durability).")
    public boolean requireFullDurability = false;

    @Comment("Items with a custom name are only sellable through a price entry that names them exactly, even if an entry accepts any name.")
    public boolean blockCustomNames = false;

    @Comment("Items whose lore contains any of these texts (case-insensitive) can never be sold.")
    public List<String> unsellableLore = new ArrayList<>(List.of("soulbound", "unsellable"));

    @Comment("Filled shulker boxes: EMPTY_ONLY = only empty boxes sell; SUM_CONTENTS = box price + contents, and only when every item inside is sellable.")
    public String containerMode = "EMPTY_ONLY";

    @Comment("Data components that never make an item 'custom' (e.g. anvil repair cost). Everything else not compared explicitly blocks plain prices.")
    public List<String> ignoredComponents = new ArrayList<>(List.of("minecraft:repair_cost"));

    @Comment("Decimal places of computed unit prices (rounded down), 0-10.")
    public int priceScale = 2;

    @Comment("/sell all, same, blocks and <material> also take matching items from the offhand. Armor is never sold.")
    public boolean includeOffhand = true;

    @Comment("Refuse /sell and /sellgui in creative or spectator mode.")
    public boolean denyInCreative = true;

    @Comment("Record every sale in the {prefix}worth_sales table (also required for the daily limit).")
    public boolean logSales = true;

    @Comment("Maximum money one player may earn by selling per day (server time zone). 0 = unlimited. Numeric permission atrium.sell.limit.<n> raises it; atrium.sell.limit.unlimited removes it.")
    public long dailyLimit = 0;

    @Comment("Rows of the /sellgui chest, 1-6.")
    public int sellGuiRows = 6;

    @Comment("Markup /generateworth adds to the ingredient cost of a recipe, in percent. 0 = exact cost.")
    public double generatePriceIncreasePercent = 10;

    @Comment("How many dependency passes /generateworth runs (1-10). Recipes that need an unpriced item\n"
            + "beyond this many steps stay unpriced.")
    public int generateMaxPasses = 5;

    @Comment("Seconds in which /generateworth confirm must follow /generateworth run (1-600).")
    public int generateConfirmSeconds = 120;
}
