package dev.yanianz.atrium.module.sourbycraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Detection of the extended API members, with stand-in types shaped like the local DEV API. */
public class ExtendedApiTest {
    /** Shaped like the extended {@code ProfileInfo}: the extra components name, members, virtualId. */
    public record ExtendedInfo(UUID id, UUID accountId, String type, String name, Map<UUID, String> members, UUID virtualId) {}

    /** Shaped like the official {@code ProfileInfo}: no extra components. */
    public record OfficialInfo(UUID id, UUID accountId, String type) {}

    /** {@code name} with another type is not the extended member. */
    public record WrongTypes(UUID id, Object name, List<String> members) {}

    public static final class ProfileUid {
        private ProfileUid() {}

        public static UUID member(UUID profile, UUID member) {
            return new UUID(profile.getMostSignificantBits(), member.getLeastSignificantBits());
        }
    }

    public interface ProfilePurse {
        String backend();
    }

    public interface ExtendedProfiles {
        CompletableFuture<Void> awaitProfileReady(UUID player);
    }

    public interface OfficialProfiles {
        boolean isEnabled();
    }

    public interface ExtendedAdapter {
        boolean required();

        void applyMissing(Player player) throws Exception;
    }

    public interface OfficialAdapter {
        String id();
    }

    private static @Nullable Class<?> lookup(String name) {
        return switch (name) {
            case ExtendedApi.PACKAGE + "ProfileUid" -> ProfileUid.class;
            case ExtendedApi.PACKAGE + "ProfilePurse" -> ProfilePurse.class;
            case "org.bukkit.entity.Player" -> Player.class;
            default -> null;
        };
    }

    private final UUID profile = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private final UUID owner = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID member = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void detectsEveryExtendedMember() {
        ExtendedApi api = ExtendedApi.detect(ExtendedInfo.class, ExtendedProfiles.class, ExtendedAdapter.class,
                ExtendedApiTest::lookup);
        assertEquals(List.of("profile-name", "members", "virtual-id", "member-uid", "purse", "await-ready",
                "adapter-required/applyMissing"), api.features());
        UUID virtual = UUID.randomUUID();
        ExtendedInfo info = new ExtendedInfo(profile, owner, "skyblock", "Mango", Map.of(owner, "OWNER", member, "MEMBER"),
                virtual);
        assertEquals("Mango", api.name(info));
        assertEquals("OWNER", api.role(info, owner));
        assertEquals("MEMBER", api.role(info, member));
        assertEquals("NONE", api.role(info, UUID.randomUUID()));
        assertEquals(virtual, api.virtualId(info));
        assertEquals(ProfileUid.member(profile, member), api.memberVirtualId(profile, member));
        assertTrue(api.purseApi());
        ProfilePurse purse = () -> "vault@Atrium";
        assertEquals("vault@Atrium", api.purseBackend(type -> type == ProfilePurse.class ? purse : null));
        assertNull(api.purseBackend(type -> null), "purse type known but no service registered");
        CompletableFuture<Void> ready = new CompletableFuture<>();
        ExtendedProfiles profiles = player -> ready;
        assertSame(ready, api.awaitReady(profiles, owner));
        assertTrue(api.adapterHooks());
    }

    @Test
    void officialShapeHasNoExtrasAndAnswersNull() {
        ExtendedApi api = ExtendedApi.detect(OfficialInfo.class, OfficialProfiles.class, OfficialAdapter.class, name -> null);
        assertEquals(List.of(), api.features());
        OfficialInfo info = new OfficialInfo(profile, owner, "skyblock");
        assertNull(api.name(info));
        assertNull(api.role(info, owner));
        assertNull(api.virtualId(info));
        assertNull(api.memberVirtualId(profile, owner));
        assertFalse(api.purseApi());
        assertNull(api.purseBackend(type -> new Object()));
        assertNull(api.awaitReady((OfficialProfiles) () -> true, owner));
        assertFalse(api.adapterHooks());
    }

    @Test
    void membersWithAnotherTypeAreNotUsed() {
        assertNull(ExtendedApi.component(WrongTypes.class, "name", String.class));
        assertNull(ExtendedApi.component(WrongTypes.class, "members", Map.class));
        assertNull(ExtendedApi.component(String.class, "name", String.class), "not a record");
    }
}
