package dev.yanianz.atrium.module.nick;

import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/nick.yml */
@ConfigSerializable
public class NickConfig {
    @Comment("Minimum visible nickname length in characters (clamped to 1-32).")
    public int minLength = 3;

    @Comment("Maximum visible nickname length in characters (clamped to 1-32).")
    public int maxLength = 16;

    @Comment("Full-match regular expression that every visible nickname character must satisfy. "
            + "Colour codes and MiniMessage tags are not part of the visible text.")
    public String allowedCharacters = "[A-Za-z0-9_-]+";

    @Comment("Words rejected anywhere in a nickname, ignoring case and common letter/number substitutions.")
    public List<String> blockedWords = List.of("admin", "administrator", "owner", "server", "staff", "moderator");

    @Comment("Prefix shown before every nickname, for example \"~\". Leave empty to disable the prefix.")
    public String prefix = "~";

    @Comment("Also apply the nickname as the player-list (tab) name. Ignored while the tablist module is enabled (it formats tab names itself).")
    public boolean updatePlayerListName = true;

    @Comment("Reject nicknames that match another player's real name, ignoring case and colour.")
    public boolean blockRealNames = true;

    @Comment("Allow a player to keep their own real name as their nickname.")
    public boolean allowOwnName = true;

    @Comment("Match blocked words after mapping 4/@->a, 0->o, 1/!/|->i, 3->e, 5/$->s, 7->t, 8->b and dropping separators.")
    public boolean normalizeBlockedWords = true;

    @Comment("Maximum raw nickname length before parsing, to bound tag-heavy input (clamped to 1-4096).")
    public int maxRawLength = 512;

    @Comment("Restore saved nicknames when a player joins.")
    public boolean restoreOnJoin = true;
}
