package dev.yanianz.atrium.module.webstore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What to do with one pending delivery, decided before it is claimed. Pure logic, no server access. */
sealed interface DeliveryPlan {
    /** Leave the row pending without using an attempt (recipient offline while require-online is set). */
    record Skip() implements DeliveryPlan {}

    /** Mark the row failed without running anything. */
    record Fail(String reason) implements DeliveryPlan {}

    /** Claim the row and run these console commands in order. */
    record Run(UUID recipient, WebstoreConfig.StorePackage storePackage, List<String> commands) implements DeliveryPlan {}

    static DeliveryPlan of(DeliveryStore.Delivery delivery, Map<String, WebstoreConfig.StorePackage> packages,
            boolean requireOnline, boolean recipientOnline) {
        WebstoreConfig.StorePackage storePackage = find(packages, delivery.packageId());
        if (storePackage == null) {
            return new Fail("unknown package: " + delivery.packageId());
        }
        UUID recipient = parseUuid(delivery.recipientUuid());
        if (recipient == null) {
            return new Fail("invalid recipient_uuid: " + delivery.recipientUuid());
        }
        if (requireOnline && !recipientOnline) {
            return new Skip();
        }
        Map<String, @Nullable String> values = CommandTemplate.values(delivery.recipientUuid(), delivery.recipientName(),
                delivery.buyerName(), delivery.packageId());
        List<String> commands = new ArrayList<>();
        for (String line : storePackage.commands == null ? List.<String>of() : storePackage.commands) {
            if (CommandTemplate.ignored(line)) {
                continue;
            }
            try {
                commands.add(CommandTemplate.apply(line, values));
            } catch (CommandTemplate.UnsafeValueException e) {
                return new Fail(e.getMessage());
            }
        }
        return new Run(recipient, storePackage, List.copyOf(commands));
    }

    /** Exact package id first, then case-insensitive. */
    static WebstoreConfig.@Nullable StorePackage find(Map<String, WebstoreConfig.StorePackage> packages, String id) {
        if (id == null || packages == null) {
            return null;
        }
        WebstoreConfig.StorePackage exact = packages.get(id);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, WebstoreConfig.StorePackage> entry : packages.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(id)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static @Nullable UUID parseUuid(String raw) {
        if (raw == null || !CommandTemplate.safe("uuid", raw.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return UUID.fromString(raw);
    }
}
