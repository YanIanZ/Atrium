package dev.yanianz.atrium.module.deathmessages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeathTemplateSelectorTest {
    private static List<String> select(String type, String direct, String causing, boolean player) {
        return DeathTemplateSelector.candidates(
                new DeathTemplateSelector.Damage(type, direct, causing, player));
    }

    @Test
    void environmentalCausesUseTheirOwnTemplateFirst() {
        assertEquals(List.of("fall", "fallback"), select("fall", null, null, false));
        assertEquals(List.of("lava", "fallback"), select("lava", null, null, false));
        assertEquals(List.of("drowning", "fallback"), select("drown", null, null, false));
        assertEquals(List.of("explosion", "fallback"), select("explosion", null, null, false));
        assertEquals(List.of("explosion", "fallback"), select("player_explosion", null, null, false));
    }

    @Test
    void playerKillsUseThePlayerTemplate() {
        assertEquals(List.of("player", "fallback"), select("player_attack", "player", "player", true));
        assertEquals(List.of("player", "fallback"), select("arrow", "arrow", "player", true));
    }

    @Test
    void environmentalCauseWinsOverAPlayerAttribution() {
        assertEquals(List.of("fall", "player", "fallback"), select("fall", null, "player", true));
        assertEquals(List.of("explosion", "player", "fallback"),
                select("player_explosion", "player", "player", true));
    }

    @Test
    void mobKillsPreferTheEntityTypeThenTheGenericMobTemplate() {
        assertEquals(List.of("mob.zombie", "mob", "fallback"),
                select("mob_attack", "zombie", "zombie", false));
        assertEquals(List.of("mob.skeleton", "mob", "projectile", "fallback"),
                select("arrow", "arrow", "skeleton", false));
    }

    @Test
    void projectilesWithoutAKillerUseTheProjectileTemplate() {
        assertEquals(List.of("projectile", "fallback"), select("arrow", "arrow", null, false));
        assertEquals(List.of("projectile", "fallback"), select("trident", null, null, false));
    }

    @Test
    void unknownCausesFallBackAndKeysAreNormalised() {
        assertEquals(List.of("fallback"), select("magic", null, null, false));
        assertEquals(List.of("mob.creeper", "mob", "fallback"),
                select("MOB_ATTACK", "minecraft:creeper", "minecraft:creeper", false));
    }

    @Test
    void explosionKillsByACreeperPreferTheExplosionTemplate() {
        assertEquals(List.of("explosion", "mob.creeper", "mob", "fallback"),
                select("explosion", "creeper", "creeper", false));
    }
}
