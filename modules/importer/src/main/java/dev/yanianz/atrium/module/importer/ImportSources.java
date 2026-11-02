package dev.yanianz.atrium.module.importer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What {@code /atrium-import sources} shows for the file-based sources: which folders and files exist and
 * what each one can import. Only reads directory listings; nothing is parsed.
 */
final class ImportSources {
    private ImportSources() {}

    /** One source; {@code detail} lists what was found, or where it was looked for when missing. */
    record Source(String name, String command, boolean available, String detail) {}

    static List<Source> scan(Path cmi, String cmiHint, Path essentials, String essentialsHint, Path essentialsSpawn,
            Path serverFolder) {
        List<Source> out = new ArrayList<>();
        if (cmi == null) {
            out.add(new Source("CMI", "/atrium-import cmi", false, "not found at " + cmiHint));
        } else {
            List<String> parts = new ArrayList<>();
            add(parts, Files.isRegularFile(cmi.resolve("config.yml")), "config.yml (database settings, spawn)");
            add(parts, exists(cmi, "Saves/Warps.yml"), "warps");
            add(parts, exists(cmi, "Saves/Holograms.yml"), "holograms");
            add(parts, exists(cmi, "Saves/Jails.yml"), "jails");
            int kits = count(cmi.resolve("Kits"), ".yml");
            add(parts, kits > 0, kits + " kit file(s)");
            boolean sqlite = false;
            for (String file : List.of("cmi.sqlite.db", "cmi.sqlite", "cmi.db", "DataBase/cmi.sqlite.db")) {
                sqlite |= Files.isRegularFile(cmi.resolve(file));
            }
            add(parts, sqlite, "SQLite database (economy, homes, nicks, playtime, last location, mutes)");
            add(parts, !sqlite, "users from the MySQL database in config.yml, if configured");
            out.add(new Source("CMI", "/atrium-import cmi", true, cmi + ": " + join(parts)));
        }
        if (essentials == null) {
            out.add(new Source("EssentialsX", "/atrium-import essentials", false, "not found at " + essentialsHint));
        } else {
            List<String> parts = new ArrayList<>();
            int users = count(essentials.resolve("userdata"), ".yml");
            add(parts, users > 0, users + " userdata file(s) (economy, homes, nicks, mail, mute/jail/ban, last location)");
            int warps = count(essentials.resolve("warps"), ".yml");
            add(parts, warps > 0, warps + " warp(s)");
            add(parts, Files.isRegularFile(essentials.resolve("kits.yml")), "kits.yml");
            add(parts, Files.isRegularFile(essentials.resolve("jail.yml"))
                    || Files.isRegularFile(essentials.resolve("jails.yml")), "jails");
            add(parts, essentialsSpawn != null && Files.isRegularFile(essentialsSpawn.resolve("spawn.yml")),
                    "EssentialsSpawn spawn.yml");
            out.add(new Source("EssentialsX", "/atrium-import essentials", true, essentials + ": " + join(parts)));
        }
        List<String> bans = new ArrayList<>();
        add(bans, serverFolder != null && Files.isRegularFile(serverFolder.resolve("banned-players.json")),
                "banned-players.json");
        add(bans, serverFolder != null && Files.isRegularFile(serverFolder.resolve("banned-ips.json")), "banned-ips.json");
        out.add(new Source("Vanilla ban lists", "/atrium-import vanilla", !bans.isEmpty(),
                bans.isEmpty() ? "no ban list in " + serverFolder : join(bans) + " (bans, temp bans, IP bans)"));
        return List.copyOf(out);
    }

    private static boolean exists(Path folder, String relative) {
        return Files.isRegularFile(folder.resolve(relative))
                || Files.isRegularFile(folder.resolve(relative.toLowerCase(Locale.ROOT)));
    }

    private static int count(Path folder, String suffix) {
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        try (var stream = Files.list(folder)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix)).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void add(List<String> parts, boolean present, String what) {
        if (present) {
            parts.add(what);
        }
    }

    private static String join(List<String> parts) {
        return parts.isEmpty() ? "folder exists but no importable file was found" : String.join(", ", parts);
    }
}
