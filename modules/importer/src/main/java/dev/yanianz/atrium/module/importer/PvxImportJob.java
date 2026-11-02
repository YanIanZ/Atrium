package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.inventories.VaultImportService;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;

/**
 * Imports PlayerVaultsX vaults ({@code plugins/PlayerVaults/newvaults/<uuid>.yml}) into the inventories
 * module's {@code /pv} vaults.
 *
 * <p>Each file holds one base64 value per vault. The payload is a slot count followed by length-prefixed,
 * gzip-compressed item NBT, which is the same item format as Paper's {@code ItemStack#serializeAsBytes};
 * it is read with the public Paper API. One player is imported all-or-nothing: when one of their vaults is
 * Java-serialized (or otherwise unreadable) none of their vaults is written, and the report says why.
 */
final class PvxImportJob extends ExtendedImportJob {
    private final Path folder;
    private VaultImportService vaultImport;

    PvxImportJob(ModuleContext ctx, ImporterConfig config, ImportOptions options, CommandSender sender, Path folder) {
        super(ctx, config, options, sender);
        this.folder = folder;
    }

    @Override
    protected String sourceName() {
        return "PlayerVaultsX";
    }

    @Override
    protected String reportPrefix() {
        return "pvx";
    }

    @Override
    protected void resolveServices() {
        super.resolveServices();
        vaultImport = ctx.services().get(VaultImportService.class).orElse(null);
        if (vaultImport == null) {
            report.note("VaultImportService is unavailable (inventories module); vaults are counted but not imported");
        }
    }

    @Override
    protected void run() throws Exception {
        if (!options.includes(ImportCategory.VAULTS)) {
            report.note("No category selected: the PlayerVaultsX import only has 'vaults'");
            return;
        }
        sourceDescription = folder.toString();
        report.note("Source: " + sourceDescription);
        report.note("Accepted vault data: " + PvxVaultCodec.acceptedFormat()
                + ". Java-serialized vaults (BukkitObjectOutputStream, magic AC ED) are refused: reading them "
                + "deserializes arbitrary classes.");
        List<Path> files;
        try (var stream = Files.list(folder)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            ctx.logger().error("Listing the PlayerVaultsX vault folder {} failed", folder, e);
            report.failed(ImportCategory.VAULTS, folder.getFileName().toString(), "cannot list folder: " + describe(e));
            return;
        }
        report.note("PlayerVaultsX vault files: " + files.size());
        beginPhase("players", files.size());
        int processed = 0;
        int every = config.progressEveryUsers();
        for (Path file : files) {
            if (isCancelled()) {
                break;
            }
            importFile(file);
            processed++;
            if (processed % every == 0) {
                reportProgress(processed);
            }
        }
        if (processed > 0 && processed % every != 0) {
            reportProgress(processed);
        }
        report.note("Players processed: " + processed + (isCancelled() ? " (cancelled)" : ""));
    }

    private void importFile(Path file) {
        String fileName = file.getFileName().toString();
        Optional<UUID> owner = ownerOf(fileName);
        if (owner.isEmpty()) {
            report.failed(ImportCategory.VAULTS, fileName, "the file name is neither a player UUID nor a name Atrium knows");
            return;
        }
        List<PvxVaultFile.Entry> entries;
        try {
            entries = PvxVaultFile.parse(loadYaml(file));
        } catch (Exception e) {
            ctx.logger().error("Reading the PlayerVaultsX vault file {} failed", file, e);
            report.failed(ImportCategory.VAULTS, fileName, "cannot read file: " + describe(e));
            return;
        }
        if (entries.isEmpty()) {
            report.note(fileName + ": no vault entry found");
            return;
        }
        String label = label(fileName, owner.get());
        int vaultCount = entries.size();

        // Decode every vault of this player first: a single refused vault refuses the whole player.
        List<PvxVaultCodec.Decoded> decoded = new ArrayList<>(entries.size());
        for (PvxVaultFile.Entry entry : entries) {
            if (entry.nonText()) {
                refuse(label, vaultCount,
                        "vault " + entry.number() + " is a YAML item map, not PlayerVaultsX's base64 format");
                return;
            }
            byte[] payload;
            try {
                payload = Base64.getMimeDecoder().decode(entry.data());
            } catch (IllegalArgumentException e) {
                report.found(ImportCategory.VAULTS, vaultCount);
                report.invalid(ImportCategory.VAULTS, label, "vault " + entry.number() + " is not valid base64");
                return;
            }
            PvxVaultCodec.Decoded result = PvxVaultCodec.decode(payload);
            if (!result.ok()) {
                if (result.refused() == null) {
                    report.found(ImportCategory.VAULTS, vaultCount);
                    report.invalid(ImportCategory.VAULTS, label,
                            "vault " + entry.number() + ": " + result.reason());
                } else {
                    refuse(label, vaultCount, "vault " + entry.number() + ": " + result.reason());
                }
                return;
            }
            decoded.add(result);
        }

        report.found(ImportCategory.VAULTS, vaultCount);
        if (vaultImport == null) {
            report.unsupported(ImportCategory.VAULTS, vaultCount,
                    "inventories module (VaultImportService) not available");
            return;
        }
        UUID uuid = owner.get();
        if (isOnline(uuid)) {
            report.skipped(ImportCategory.VAULTS, vaultCount, "player online (re-run after they log out)");
            return;
        }
        List<VaultImportService.Vault> vaults;
        try {
            vaults = await(ctx.scheduler().supplyGlobal(() -> build(entries, decoded)), ImportCategory.VAULTS);
        } catch (StepFailed failure) {
            report.failed(ImportCategory.VAULTS, label, "item data could not be decoded: " + failure.getMessage());
            return;
        }
        Set<Integer> existing;
        try {
            existing = await(vaultImport.existingVaults(uuid), ImportCategory.VAULTS);
        } catch (StepFailed failure) {
            report.failed(ImportCategory.VAULTS, label, "cannot read the existing vaults: " + failure.getMessage());
            return;
        }
        if (!existing.isEmpty() && !options.overwrite()) {
            report.skipped(ImportCategory.VAULTS, vaultCount, "vaults already exist in Atrium (use --overwrite)");
            return;
        }
        if (!options.overwrite() && alreadyImported(ledgerSource(), ImportCategory.VAULTS, uuid)) {
            report.skipped(ImportCategory.VAULTS, vaultCount, "vaults already imported (use --overwrite)");
            return;
        }
        if (options.dryRun()) {
            report.imported(ImportCategory.VAULTS, vaultCount);
            return;
        }
        try {
            mark(ledgerSource(), ImportCategory.VAULTS, uuid);
            VaultImportService.Result result = awaitWrite(vaultImport.importVaults(uuid, vaults, options.overwrite()),
                    ImportCategory.VAULTS, "vaults of " + label);
            switch (result) {
                case IMPORTED -> report.imported(ImportCategory.VAULTS, vaultCount);
                case SKIPPED -> {
                    report.skipped(ImportCategory.VAULTS, vaultCount,
                            "vaults appeared while importing (use --overwrite)");
                    unmark(ledgerSource(), ImportCategory.VAULTS, uuid, label);
                }
                case INVALID -> {
                    report.failed(ImportCategory.VAULTS, vaultCount, label,
                            "rejected by the inventories module (invalid vault or slot count)");
                    unmark(ledgerSource(), ImportCategory.VAULTS, uuid, label);
                }
            }
        } catch (StepFailed failure) {
            report.failed(ImportCategory.VAULTS, vaultCount, label, failure.getMessage());
            unmark(ledgerSource(), ImportCategory.VAULTS, uuid, label);
        }
    }

    /** One refused format refuses the whole player, so no half a vault list is written. */
    private void refuse(String label, int vaultCount, String reason) {
        report.found(ImportCategory.VAULTS, vaultCount);
        report.unsupported(ImportCategory.VAULTS, vaultCount, reason);
        report.issue(new ImportIssue(ImportCategory.VAULTS, label,
                "not imported, nothing of this player was written: " + reason));
        noteOnce("pvx-format", "PlayerVaultsX vaults stored with Java object serialization are refused (unsafe "
                + "deserialization). With PlayerVaultsX installed, open each vault and copy the items, or ask its "
                + "author for an NBT export.");
    }

    /** Runs on the global thread: item decoding needs the server registries. */
    private static List<VaultImportService.Vault> build(List<PvxVaultFile.Entry> entries,
            List<PvxVaultCodec.Decoded> decoded) {
        List<VaultImportService.Vault> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            PvxVaultCodec.Decoded vault = decoded.get(i);
            List<ItemStack> items = new ArrayList<>(vault.items().size());
            for (byte[] bytes : vault.items()) {
                if (bytes == null) {
                    items.add(null);
                    continue;
                }
                ItemStack item = ItemStack.deserializeBytes(bytes);
                items.add(item == null || item.getType().isAir() ? null : item);
            }
            out.add(new VaultImportService.Vault(entries.get(i).number(), rows(vault.slots()), items));
        }
        return List.copyOf(out);
    }

    private static int rows(int slots) {
        return Math.max(1, Math.min(VaultImportService.MAX_ROWS, (slots + VaultImportService.COLUMNS - 1)
                / VaultImportService.COLUMNS));
    }

    /** The owner from a UUID-named file, or a legacy name the server still knows. */
    private Optional<UUID> ownerOf(String fileName) {
        String id = EssentialsUserParser.fileId(fileName);
        UUID parsed = EssentialsUserParser.uuidFromId(id);
        if (parsed != null) {
            return Optional.of(parsed);
        }
        try {
            return await(ctx.users().loadByName(id), ImportCategory.VAULTS).map(AtriumUser::uuid);
        } catch (StepFailed failure) {
            report.failed(ImportCategory.VAULTS, fileName,
                    "cannot resolve player name '" + id + "': " + failure.getMessage());
            return Optional.empty();
        }
    }

    private String label(String fileName, UUID owner) {
        return ctx.users().cached(owner).map(AtriumUser::name).orElse(fileName);
    }
}
