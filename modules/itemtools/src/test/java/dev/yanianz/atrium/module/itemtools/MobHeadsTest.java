package dev.yanianz.atrium.module.itemtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MobHeadsTest {
    @Test
    void idsFollowTheCommandAlphabet() {
        assertTrue(MobHeads.validId("zombie"));
        assertTrue(MobHeads.validId("wither-skeleton"));
        assertTrue(MobHeads.validId("a_b-9"));
        assertFalse(MobHeads.validId("Zombie"));
        assertFalse(MobHeads.validId("two words"));
        assertFalse(MobHeads.validId(""));
        assertFalse(MobHeads.validId(null));
        assertFalse(MobHeads.validId("x".repeat(33)));
    }

    @Test
    void texturesMustBeReadableBase64WithinBounds() {
        assertTrue(MobHeads.validTexture(""));
        assertTrue(MobHeads.validTexture(null));
        assertTrue(MobHeads.validTexture("aGVsbG8="));
        assertFalse(MobHeads.validTexture("not base64 !!!"));
        assertFalse(MobHeads.validTexture("a".repeat(9000)));
        assertFalse(MobHeads.validTexture("===="));
    }

    @Test
    void parseSkipsBrokenEntriesWithoutFailingTheRest() {
        List<ItemToolsConfig.MobHead> configured = new ArrayList<>(List.of(
                new ItemToolsConfig.MobHead("zombie", "ZOMBIE_HEAD", ""),
                new ItemToolsConfig.MobHead("Bad Id", "ZOMBIE_HEAD", ""),
                new ItemToolsConfig.MobHead("stone", "STONE", ""),
                new ItemToolsConfig.MobHead("bad texture", "PLAYER_HEAD", "%%%"),
                new ItemToolsConfig.MobHead("zombie", "SKELETON_SKULL", "")));
        List<String> problems = new ArrayList<>();
        List<MobHeads.Entry> entries = MobHeads.parse(configured, problems::add);
        assertEquals(1, entries.size());
        assertEquals("zombie", entries.getFirst().id());
        assertEquals(4, problems.size(), problems.toString());
    }

    @Test
    void findIsCaseInsensitive() {
        List<MobHeads.Entry> entries = List.of(new MobHeads.Entry("zombie", org.bukkit.Material.ZOMBIE_HEAD, ""));
        assertTrue(MobHeads.find(entries, "ZOMBIE").isPresent());
        assertTrue(MobHeads.find(entries, " skeleton ").isEmpty());
        assertTrue(MobHeads.find(entries, null).isEmpty());
    }
}
