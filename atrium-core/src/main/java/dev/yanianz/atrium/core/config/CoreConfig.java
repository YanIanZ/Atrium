package dev.yanianz.atrium.core.config;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/config.yml */
@ConfigSerializable
public class CoreConfig {
    @Comment("Locale used for the console and for players whose client locale has no translation (e.g. en_US, id_ID).")
    public String defaultLocale = "en_US";

    @Comment("Use each player's client language when a translation exists.")
    public boolean perPlayerLocale = true;

    @Comment("Storage backend shared by all modules.")
    public Database database = new Database();

    @Comment("Seconds between background saves of changed player data.")
    public int userSaveIntervalSeconds = 30;

    @Comment("Seconds to wait for player data during login before refusing the login.")
    public int userLoadTimeoutSeconds = 10;

    @Comment("Minutes an offline player's data stays cached after it was last used.")
    public int offlineCacheMinutes = 10;

    @Comment("Commands that are not registered at all (name without slash). Requires a restart.")
    public List<String> disabledCommands = new ArrayList<>();

    @Comment("Keep another plugin's command when it already registers the same name (for example EssentialsX\n"
            + "or WorldEdit). Atrium then skips its own command for that name or alias and lists the decision in\n"
            + "the console. Requires a restart; disabled-commands always wins over this option.")
    public boolean preferOtherPluginCommands = false;

    @Comment("Colours and GUI defaults shared by every module. Lang files use them as MiniMessage tags:\n"
            + "<primary>, <secondary>, <accent>, <muted>, <success>, <warning>, <error>, <highlight>.\n"
            + "Values are hex (#5FB3A1) or named colours (gray, green, ...). Applied by /atrium reload.")
    public ThemeSettings theme = new ThemeSettings();

    @ConfigSerializable
    public static class Database {
        @Comment("SQLITE, MYSQL or MARIADB")
        public String type = "SQLITE";
        public String host = "localhost";
        public int port = 3306;
        public String database = "atrium";
        public String username = "atrium";
        public String password = "";
        @Comment("Prefix for every table name.")
        public String tablePrefix = "atrium_";
        public int poolSize = 8;
        @Comment("Extra JDBC URL parameters for MySQL/MariaDB (MariaDB Connector/J syntax). When empty, TLS with\n"
                + "certificate verification (sslMode=verify-full) is used for remote hosts and no TLS for localhost.")
        public String parameters = "";
    }

    @ConfigSerializable
    public static class ThemeSettings {
        @Comment("Main brand colour: titles, command names, important values.")
        public String primary = "#5FB3A1";
        @Comment("Second brand colour: sub-headings, secondary values.")
        public String secondary = "#3B7F9A";
        @Comment("Accent for small highlights: keys, counters, links.")
        public String accent = "#F2C46D";
        @Comment("Quiet text: explanations, separators, hints.")
        public String muted = "gray";
        @Comment("Positive results.")
        public String success = "#6BCB77";
        @Comment("Warnings and partial results.")
        public String warning = "#F5A65B";
        @Comment("Errors and denials.")
        public String error = "#E5534B";
        @Comment("Inserted values (player names, amounts) inside a sentence.")
        public String highlight = "white";
        @Comment("MiniMessage for the <prefix> tag (theme tags work here). Empty = use 'prefix' from lang/<locale>/core.yml.")
        public String prefix = "";
        @Comment("Defaults for chest menus.")
        public GuiSettings gui = new GuiSettings();
    }

    @ConfigSerializable
    public static class GuiSettings {
        @Comment("Material that fills empty menu slots. Empty = leave slots empty.")
        public String filler = "GRAY_STAINED_GLASS_PANE";
        @Comment("Sound key played when a menu opens. Empty = silent.")
        public String openSound = "minecraft:ui.button.click";
        @Comment("Sound key played when a menu button is clicked. Empty = silent.")
        public String clickSound = "minecraft:ui.button.click";
        @Comment("Sound key played when an action in a menu fails. Empty = silent.")
        public String errorSound = "minecraft:entity.villager.no";
        @Comment("Volume of the menu sounds (0.0 - 1.0).")
        public double soundVolume = 0.5;
        @Comment("Minimum milliseconds between two handled clicks of one player (stops click spam and macro dupes).")
        public int clickDebounceMillis = 150;
    }
}
