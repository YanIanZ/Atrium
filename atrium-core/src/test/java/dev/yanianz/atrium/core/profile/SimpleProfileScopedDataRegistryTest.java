package dev.yanianz.atrium.core.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.profile.ProfileScopedData;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class SimpleProfileScopedDataRegistryTest {
    private static ProfileScopedData data(String id) {
        return new ProfileScopedData() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public byte[] capture(UUID player) {
                return null;
            }

            @Override
            public CompletableFuture<Void> apply(UUID player, byte[] bytes) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    @Test
    void registersSortedAndRefusesDuplicatesAndBadIds() {
        SimpleProfileScopedDataRegistry registry = new SimpleProfileScopedDataRegistry();
        ProfileScopedData homes = data("homes");
        registry.register(data("kits"));
        registry.register(homes);
        registry.register(homes);
        assertEquals(List.of("homes", "kits"), registry.all().stream().map(ProfileScopedData::id).toList());
        assertThrows(IllegalStateException.class, () -> registry.register(data("homes")));
        assertThrows(IllegalArgumentException.class, () -> registry.register(data("Bad Id")));
        registry.unregister(data("homes"));
        assertTrue(registry.get("homes").isPresent(), "only the registered instance is removed");
        registry.unregister(homes);
        assertTrue(registry.get("homes").isEmpty());
    }
}
