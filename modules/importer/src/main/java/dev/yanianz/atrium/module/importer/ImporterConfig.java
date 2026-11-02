package dev.yanianz.atrium.module.importer;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/importer.yml */
@ConfigSerializable
public class ImporterConfig {
    @Comment("CMI data folder. Leave empty to use plugins/CMI. A relative path is resolved against the\n"
            + "plugins folder; an absolute path is used as-is.")
    public String cmiFolder = "";

    @Comment("EssentialsX data folder (it contains userdata/ and warps/). Leave empty to use\n"
            + "plugins/Essentials. A relative path is resolved against the plugins folder.")
    public String essentialsFolder = "";

    @Comment("EssentialsSpawn data folder (it contains spawn.yml). Leave empty to use\n"
            + "plugins/EssentialsSpawn next to Essentials.")
    public String essentialsSpawnFolder = "";

    @Comment("Users read per source batch (clamped to 1-1000).")
    public int batchSize = 50;

    @Comment("Print a progress line every N processed records (clamped to 1-100000).")
    public int progressEveryUsers = 100;

    @Comment("Maximum problems per category shown in the console; the report file keeps everything (1-1000).")
    public int maxProblemsPerCategory = 10;

    @Comment("Seconds to wait for one read (balance, homes, nickname, playtime) before the user import is\n"
            + "considered failed (5-300). Writes are never abandoned on a deadline; a slow write is awaited\n"
            + "until its real result is known.")
    public int operationTimeoutSeconds = 60;

    @Comment("Import holograms that CMI stored with Enabled: false.")
    public boolean importDisabledHolograms = false;

    @Comment("Skip homes, warps and holograms whose world folder no longer exists.")
    public boolean checkWorlds = true;

    @Comment("Folder under plugins/Atrium for /atrium-backup files.")
    public String backupDirectory = "backups";

    @Comment("Number of Atrium SQLite backups kept; older files are deleted (1-1000).")
    public int backupKeep = 10;

    public int batchSize() {
        return Math.clamp(batchSize, 1, 1000);
    }

    public int progressEveryUsers() {
        return Math.clamp(progressEveryUsers, 1, 100_000);
    }

    public int maxProblemsPerCategory() {
        return Math.clamp(maxProblemsPerCategory, 1, 1000);
    }

    public int operationTimeoutSeconds() {
        return Math.clamp(operationTimeoutSeconds, 5, 300);
    }

    public int backupKeep() {
        return Math.clamp(backupKeep, 1, 1000);
    }

    public String backupDirectory() {
        return backupDirectory == null || backupDirectory.isBlank() ? "backups" : backupDirectory;
    }

    public String cmiFolder() {
        return cmiFolder == null ? "" : cmiFolder.trim();
    }

    public String essentialsFolder() {
        return essentialsFolder == null ? "" : essentialsFolder.trim();
    }

    public String essentialsSpawnFolder() {
        return essentialsSpawnFolder == null ? "" : essentialsSpawnFolder.trim();
    }
}
