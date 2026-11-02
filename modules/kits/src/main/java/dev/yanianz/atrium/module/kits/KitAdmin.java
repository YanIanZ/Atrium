package dev.yanianz.atrium.module.kits;

import dev.yanianz.atrium.api.kits.KitAdminService;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.inventory.ItemStack;

/** {@link KitAdminService}: kit writes for import tools, through the same store and cache as the editor. */
final class KitAdmin implements KitAdminService {
    private final KitService kits;
    private final Consumer<Kit> registerPermission;

    KitAdmin(KitService kits, Consumer<Kit> registerPermission) {
        this.kits = kits;
        this.registerPermission = registerPermission;
    }

    @Override
    public Collection<String> kitNames() {
        return Kit.sortedNames(kits.kits());
    }

    @Override
    public CompletableFuture<Boolean> importKit(KitImport input, boolean overwrite) {
        Kit kit;
        try {
            kit = build(input);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e instanceof IllegalArgumentException ? e
                    : new IllegalArgumentException("invalid kit " + input.name() + ": " + e.getMessage(), e));
        }
        Optional<Kit> existing = kits.kit(kit.name());
        if (existing.isPresent()) {
            if (!overwrite) {
                return CompletableFuture.completedFuture(false);
            }
            Kit replaced = kit.withVersion(existing.get().version());
            return kits.store().updateKit(replaced).thenApply(stored -> {
                if (stored.isEmpty()) {
                    kits.refresh(kit.name());
                    throw new IllegalStateException("kit " + kit.name() + " changed while it was imported; run again");
                }
                kits.put(stored.get());
                registerPermission.accept(stored.get());
                return true;
            });
        }
        return kits.store().createKit(kit).thenApply(created -> {
            if (created) {
                kits.put(kit);
                registerPermission.accept(kit);
            }
            return created;
        });
    }

    private static Kit build(KitImport input) {
        String name = input.name().trim();
        if (!Kit.validName(name)) {
            throw new IllegalArgumentException("invalid or reserved kit name: " + input.name());
        }
        List<ItemStack> slots = input.slots();
        if (slots.size() > SLOTS) {
            throw new IllegalArgumentException("kit " + name + " has more than " + SLOTS + " slots");
        }
        if (input.permission() != null && !input.permission().isBlank() && !Kit.validPermission(input.permission())) {
            throw new IllegalArgumentException("invalid kit permission: " + input.permission());
        }
        ItemStack[] layout = new ItemStack[KitItems.SIZE];
        for (int i = 0; i < slots.size(); i++) {
            ItemStack item = slots.get(i);
            layout[i] = item == null || item.isEmpty() ? null : item.clone();
        }
        return new Kit(name, Math.max(0, input.cooldownSeconds()), input.oneTime(), input.firstJoin(), input.cost(),
                KitItems.serialize(layout), KitItems.serializeIcon(input.icon()), input.permission(),
                Math.max(-1, input.maxUses()), System.currentTimeMillis());
    }
}
