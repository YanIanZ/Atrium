package dev.yanianz.atrium.module.webstore;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryPlanTest {
    private static final String RECIPIENT = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private final Map<String, WebstoreConfig.StorePackage> packages = new WebstoreConfig().packages;

    private static DeliveryStore.Delivery row(String packageId, String uuid, String buyer) {
        return new DeliveryStore.Delivery(1, "order-1", packageId, uuid, "Steve", buyer, false, null, 0);
    }

    @Test void shipsTheExpectedDefaultPackages() {
        assertEquals(List.of("vip", "mvp", "mvp_plus", "ultra", "supreme", "zenith"), List.copyOf(packages.keySet()));
        for (var entry : packages.entrySet()) {
            assertEquals(List.of("lp user {uuid} parent add " + entry.getKey()), entry.getValue().commands);
        }
    }

    @Test void runsTheCommandsOfAKnownPackage() {
        var plan = assertInstanceOf(DeliveryPlan.Run.class, DeliveryPlan.of(row("mvp_plus", RECIPIENT, null), packages, false, false));
        assertEquals(UUID.fromString(RECIPIENT), plan.recipient());
        assertEquals(List.of("lp user " + RECIPIENT + " parent add mvp_plus"), plan.commands());
        // Package ids are matched case-insensitively as a fallback.
        assertInstanceOf(DeliveryPlan.Run.class, DeliveryPlan.of(row("MVP_PLUS", RECIPIENT, null), packages, false, false));
    }

    @Test void unknownPackageFails() {
        var plan = assertInstanceOf(DeliveryPlan.Fail.class, DeliveryPlan.of(row("legend", RECIPIENT, null), packages, false, true));
        assertTrue(plan.reason().contains("unknown package"), plan.reason());
    }

    @Test void invalidRecipientUuidFails() {
        assertInstanceOf(DeliveryPlan.Fail.class, DeliveryPlan.of(row("vip", "Steve", null), packages, false, true));
    }

    @Test void requireOnlineSkipsOfflineRecipientsOnly() {
        assertInstanceOf(DeliveryPlan.Skip.class, DeliveryPlan.of(row("vip", RECIPIENT, null), packages, true, false));
        assertInstanceOf(DeliveryPlan.Run.class, DeliveryPlan.of(row("vip", RECIPIENT, null), packages, true, true));
        assertInstanceOf(DeliveryPlan.Run.class, DeliveryPlan.of(row("vip", RECIPIENT, null), packages, false, false));
    }

    @Test void unsafeValueInACommandFailsBeforeAnythingRuns() {
        var custom = Map.of("shout", WebstoreConfig.StorePackage.of("Shout", "lp user {uuid} parent add vip", "say thanks {buyer}"));
        var plan = assertInstanceOf(DeliveryPlan.Fail.class,
                DeliveryPlan.of(row("shout", RECIPIENT, "Bob op Bob"), custom, false, true));
        assertTrue(plan.reason().contains("{buyer}"), plan.reason());
    }

    @Test void commentAndBlankLinesAreSkipped() {
        var custom = Map.of("p", WebstoreConfig.StorePackage.of("P", "# grant", "", "/lp user {uuid} parent add vip"));
        var plan = assertInstanceOf(DeliveryPlan.Run.class, DeliveryPlan.of(row("p", RECIPIENT, null), custom, false, true));
        assertEquals(List.of("lp user " + RECIPIENT + " parent add vip"), plan.commands());
    }
}
