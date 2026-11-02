package dev.yanianz.atrium.module.inventories;

import dev.yanianz.atrium.api.inventories.VaultImportService;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;

/**
 * {@link VaultImportService} over the personal vault table, used by the importer.
 *
 * <p>The item bytes are produced on the global thread (item serialization needs the server registries),
 * then every vault of one owner is written in one transaction. Nothing is written for an owner when one
 * of their vaults already exists and {@code overwrite} is unset.
 */
final class VaultImportWriter implements VaultImportService {
    private final ModuleContext ctx;
    private final VaultStore store;

    VaultImportWriter(ModuleContext ctx, VaultStore store) {
        this.ctx = ctx;
        this.store = store;
    }

    @Override
    public CompletableFuture<Set<Integer>> existingVaults(UUID owner) {
        if (owner == null) {
            return CompletableFuture.completedFuture(Set.of());
        }
        return store.list(owner).thenApply(Set::copyOf);
    }

    @Override
    public CompletableFuture<Result> importVaults(UUID owner, List<Vault> vaults, boolean overwrite) {
        if (owner == null) {
            return CompletableFuture.completedFuture(Result.INVALID);
        }
        List<Vault> incoming = vaults == null ? List.of() : List.copyOf(vaults);
        if (incoming.isEmpty()) {
            return CompletableFuture.completedFuture(Result.INVALID);
        }
        Set<Integer> seen = new java.util.HashSet<>();
        for (Vault vault : incoming) {
            if (!seen.add(vault.number())) {
                return CompletableFuture.completedFuture(Result.INVALID);
            }
        }
        return ctx.scheduler().supplyGlobal(() -> encode(incoming)).thenCompose(drafts ->
                store.importAll(owner, drafts, overwrite)
                        .thenApply(written -> written ? Result.IMPORTED : Result.SKIPPED));
    }

    /** Runs on the global thread: item bytes need the server registries. */
    private static List<VaultStore.Draft> encode(List<Vault> vaults) {
        List<VaultStore.Draft> drafts = new ArrayList<>(vaults.size());
        for (Vault vault : vaults) {
            List<ItemStack> slots = vault.items() == null ? List.of() : vault.items();
            VaultLayout.Fit<ItemStack> fit = VaultLayout.fit(slots, vault.slots());
            byte[] bytes = VaultItems.encode(fit.kept());
            drafts.add(new VaultStore.Draft(vault.number(), vault.rows(), vault.slots(), bytes));
        }
        return List.copyOf(drafts);
    }

    /** The rows a source slot count needs, at most six. */
    static int rowsFor(int slots) {
        return Math.max(1, Math.min(MAX_ROWS, (Math.max(0, slots) + COLUMNS - 1) / COLUMNS));
    }
}
