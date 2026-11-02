package dev.yanianz.atrium.module.skins;

import java.util.Base64;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The {@code textures} profile property of an account, exactly as Mojang signed it. Atrium never builds texture
 * values itself and never fetches the URL inside; the client loads the skin.
 *
 * @param signature Mojang's signature; {@code null} when the profile service returned none
 */
record SkinTexture(String value, @Nullable String signature) {
    static final int MAX_VALUE_LENGTH = 32_768;

    SkinTexture {
        Objects.requireNonNull(value, "value");
        if (signature != null && signature.isEmpty()) {
            signature = null;
        }
    }

    /** A value that is non-empty, bounded and valid base64. Rows failing this are treated as missing. */
    static boolean wellFormed(@Nullable String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_VALUE_LENGTH) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(value).length > 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
