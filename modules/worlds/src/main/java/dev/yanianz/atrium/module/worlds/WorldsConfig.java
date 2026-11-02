package dev.yanianz.atrium.module.worlds;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/worlds.yml */
@ConfigSerializable
public class WorldsConfig {
    @Comment("Order in which the world providers are asked about a world name. The first provider that knows a\n"
            + "name (or alias) owns it: it loads it and gives its spawn. Ids: multiverse, awf (SourbyCraft Aurora\n"
            + "World Fabric), slime (Advanced Slime Paper), bukkit (already loaded worlds). Providers missing here\n"
            + "are asked last. Applied on /atrium reload.")
    public List<String> providerOrder = new ArrayList<>(List.of("multiverse", "awf", "slime", "bukkit"));

    @Comment("Load a known but unloaded world when someone needs it (/wtp, and homes, warps and /back of the\n"
            + "teleport module). When false, only loaded worlds can be reached.")
    public boolean loadOnDemand = true;

    @Comment("Seconds to wait for a world to load before giving up (5-300).")
    public int loadTimeoutSeconds = 60;

    @Comment("When true, /wtp into a world also needs atrium.wtp.world.<world> (lower case) or atrium.wtp.world.*.\n"
            + "When false, atrium.wtp is enough; an explicitly denied atrium.wtp.world.<world> still blocks.")
    public boolean requireWorldPermission = false;

    @Comment("Also register /mvtp as an alias of /wtp, but only when Multiverse-Core is NOT installed (its own\n"
            + "/mvtp keeps working). Needs a restart.")
    public boolean mvtpAlias = true;

    @Comment("Worlds per page of /worlds (3-50).")
    public int pageSize = 10;

    @Comment("Seconds a /worlds unload confirmation stays valid (5-300).")
    public int unloadConfirmSeconds = 30;

    @Comment("Multiverse-Core 5 provider (Paper only). Needs a restart to turn on or off.")
    public Multiverse multiverse = new Multiverse();

    @Comment("Advanced Slime Paper provider (needs the ASP server). Needs a restart to turn on or off.")
    public Slime slime = new Slime();

    @ConfigSerializable
    public static class Multiverse {
        @Comment("Use the Multiverse-Core provider when Multiverse-Core 5 is installed.")
        public boolean enabled = true;

        @Comment("When Multiverse enforces access (enforce-access in its config), /wtp also needs\n"
                + "multiverse.access.<world> for the player who travels.")
        public boolean respectAccessPermissions = true;
    }

    @ConfigSerializable
    public static class Slime {
        @Comment("Use the Advanced Slime Paper provider when the ASP API is present.")
        public boolean enabled = true;

        @Comment("Where slime worlds are listed and read from: file, mysql, mongodb or api. 'file' works with the\n"
                + "ASP server alone; mysql, mongodb and api use the loaders of the ASPaperPlugin, which must be\n"
                + "installed.")
        public String loader = "file";

        @Comment("Load slime worlds read-only (changes are never saved back).")
        public boolean readOnly = false;

        @Comment("Seconds between refreshes of the list of slime worlds in the loader (10-3600).")
        public int listRefreshSeconds = 60;

        @Comment("file loader: directory of the .slime files, relative to the server folder (ASP's default is slime_worlds).")
        public String filePath = "slime_worlds";

        @Comment("mysql loader settings (same meaning as in ASP's sources.yml).")
        public Mysql mysql = new Mysql();

        @Comment("mongodb loader settings (same meaning as in ASP's sources.yml).")
        public Mongo mongodb = new Mongo();

        @Comment("api loader settings (same meaning as in ASP's sources.yml).")
        public Api api = new Api();
    }

    @ConfigSerializable
    public static class Mysql {
        @Comment("JDBC URL; {host}, {port}, {database} and {usessl} are filled in.")
        public String url = "jdbc:mysql://{host}:{port}/{database}?autoReconnect=true&allowMultiQueries=true&useSSL={usessl}";
        @Comment("Database host.")
        public String host = "127.0.0.1";
        @Comment("Database port.")
        public int port = 3306;
        @Comment("Database name.")
        public String database = "slimeworldmanager";
        @Comment("Use SSL.")
        public boolean useSsl = false;
        @Comment("User name.")
        public String username = "slimeworldmanager";
        @Comment("Password.")
        public String password = "";
    }

    @ConfigSerializable
    public static class Mongo {
        @Comment("Connection URI; when set, host/port/username/password are ignored.")
        public String uri = "";
        @Comment("Host.")
        public String host = "127.0.0.1";
        @Comment("Port.")
        public int port = 27017;
        @Comment("Authentication database.")
        public String authSource = "admin";
        @Comment("User name.")
        public String username = "slimeworldmanager";
        @Comment("Password.")
        public String password = "";
        @Comment("Database name.")
        public String database = "slimeworldmanager";
        @Comment("Collection name.")
        public String collection = "worlds";
    }

    @ConfigSerializable
    public static class Api {
        @Comment("Base URL of the world API.")
        public String url = "";
        @Comment("User name.")
        public String username = "";
        @Comment("Token.")
        public String token = "";
        @Comment("Accept any TLS certificate (testing only).")
        public boolean ignoreSslCertificate = false;
    }
}
