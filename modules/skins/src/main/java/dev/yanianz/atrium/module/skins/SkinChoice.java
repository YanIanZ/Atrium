package dev.yanianz.atrium.module.skins;

import org.jspecify.annotations.Nullable;

/**
 * The skin a player chose, persisted so it is applied again on join without a network request.
 *
 * @param skinName  account name the skin was taken from; {@code null} when the player uses their own skin
 * @param texture   the textures property; {@code null} together with {@code skinName}
 * @param updatedAt epoch milliseconds when the texture was fetched
 */
record SkinChoice(@Nullable String skinName, @Nullable SkinTexture texture, long updatedAt) {
    static final SkinChoice NONE = new SkinChoice(null, null, 0L);

    SkinChoice {
        if (skinName == null || texture == null) {
            skinName = null;
            texture = null;
        }
    }

    boolean custom() {
        return texture != null;
    }
}
