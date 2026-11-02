package dev.yanianz.atrium.module.sourbycraft;

import dev.iyanz.sourbycraft.api.profile.ProfileInfo;
import dev.yanianz.atrium.api.profile.ActiveProfileService.ActiveProfile;
import java.util.UUID;

/**
 * SourbyCraft {@link ProfileInfo} to Atrium's {@link ActiveProfile}. Hook class: loaded only on SourbyCraft.
 *
 * <p>The official API's profile has an owning account, a type and metadata, but no name, member list or
 * virtual ids. Baseline values: name = metadata {@code name} (else the type), role = {@code OWNER} for the
 * owning account (else {@code NONE}), both virtual ids = the profile id. On an extended API
 * ({@link ExtendedApi}) the real name, role and virtual ids are used.
 */
final class ProfileMapping {
    static final String NAME_METADATA = "name";

    private ProfileMapping() {}

    static ActiveProfile toActive(ProfileInfo info, UUID player, ExtendedApi extended) {
        String name = extended.name(info);
        if (name == null || name.isBlank()) {
            String meta = info.metadata().get(NAME_METADATA);
            name = meta == null || meta.isBlank() ? info.type() : meta;
        }
        String role = extended.role(info, player);
        if (role == null) {
            role = info.accountId().equals(player) ? "OWNER" : "NONE";
        }
        UUID virtualId = extended.virtualId(info);
        UUID memberVirtualId = extended.memberVirtualId(info.id(), player);
        return new ActiveProfile(info.id(), name, info.type(), role, info.status(),
                virtualId == null ? info.id() : virtualId, memberVirtualId == null ? info.id() : memberVirtualId);
    }
}
