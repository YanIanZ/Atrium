package dev.yanianz.atrium.api.kits;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Administrative kit writes for import tools, published by the {@code kits} module. Players and admins
 * keep using {@code /kit create} and the kit editor.
 */
public interface KitAdminService {
    /** Number of item slots: storage 0-35, armor 36-39 (boots, leggings, chestplate, helmet), offhand 40. */
    int SLOTS = 41;

    /**
     * One kit to import.
     *
     * @param slots           up to {@link #SLOTS} items by slot; {@code null} entries are empty slots
     * @param cooldownSeconds 0 for no cooldown
     * @param cost            charged on claim; zero for free
     * @param permission      node needed to claim, or {@code null} for {@code atrium.kit.<name>}
     * @param maxUses         -1 for unlimited
     * @param icon            menu icon, or {@code null} for the default
     */
    record KitImport(String name, List<@Nullable ItemStack> slots, long cooldownSeconds, BigDecimal cost,
            @Nullable String permission, int maxUses, boolean oneTime, boolean firstJoin, @Nullable ItemStack icon) {
        public KitImport {
            Objects.requireNonNull(name, "name");
            slots = java.util.Collections.unmodifiableList(new ArrayList<>(slots));
            cost = cost == null ? BigDecimal.ZERO : cost;
        }
    }

    /** Names of the existing kits (lower case). */
    Collection<String> kitNames();

    /**
     * Creates the kit, or replaces every setting and its items when {@code overwrite} is true. Invalid
     * names, permissions, costs or more than {@link #SLOTS} slots fail with {@link IllegalArgumentException}.
     *
     * @return {@code true} when written, {@code false} when it exists and {@code overwrite} is false
     */
    CompletableFuture<Boolean> importKit(KitImport kit, boolean overwrite);
}
