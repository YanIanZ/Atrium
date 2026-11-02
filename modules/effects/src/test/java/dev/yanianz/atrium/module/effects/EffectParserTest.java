package dev.yanianz.atrium.module.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.FireworkEffect;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

class EffectParserTest {
    private static EffectsConfig.EventEffect valid(String particle) {
        EffectsConfig.EventEffect effect = new EffectsConfig.EventEffect();
        effect.particle.particle = particle;
        effect.particle.shape = "ring";
        effect.particle.radius = 2.0;
        effect.particle.points = 20;
        effect.sound.key = "ENTITY_PLAYER_LEVELUP";
        effect.sound.volume = 0.8;
        effect.sound.pitch = 1.2;
        effect.title.title = "<primary>Hello</primary>";
        effect.title.subtitle = "<muted>There</muted>";
        return effect;
    }

    @Test void parsesACompleteDefinition() {
        Map<String, EffectsConfig.EventEffect> defs = new LinkedHashMap<>();
        EffectsConfig.EventEffect raw = valid("HAPPY_VILLAGER");
        raw.cooldownSeconds = 30;
        raw.viewRadius = 1000;
        raw.permission = "atrium.effects.join";
        raw.firework.enabled = true;
        raw.firework.type = "STAR";
        raw.firework.colours = List.of("#ff8800", "bad-colour");
        defs.put("Join", raw);

        EffectParser.Result result = EffectParser.parse(defs);
        assertEquals(1, result.effects().size());
        EffectStyle style = result.effects().get("join");
        assertNotNull(style);
        assertTrue(style.enabled());
        assertEquals("atrium.effects.join", style.permission());
        assertEquals(Duration.ofSeconds(30), style.cooldown());
        assertEquals(64.0, style.radius(), 1e-9, "view radius is clamped to 64");
        assertEquals(Shape.RING, style.particles().shape());
        assertEquals(Particle.HAPPY_VILLAGER, style.particles().particle());
        assertEquals(20, style.particles().points());
        assertEquals("ENTITY_PLAYER_LEVELUP", style.sound().key());
        assertEquals(0.8f, style.sound().volume(), 1e-6);
        assertEquals("<primary>Hello</primary>", style.title().title());
        assertEquals(FireworkEffect.Type.STAR, style.firework().type());
        assertEquals(1, style.firework().colours().size(), "the broken colour is dropped, the good one kept");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("invalid colour")), "warn about bad colour");
    }

    @Test void oneBrokenEntryDoesNotBreakTheOthers() {
        Map<String, EffectsConfig.EventEffect> defs = new LinkedHashMap<>();
        EffectsConfig.EventEffect broken = valid("NOT_A_PARTICLE");
        defs.put("join", broken);
        defs.put("death", valid("FLAME"));

        EffectParser.Result result = EffectParser.parse(defs);
        assertEquals(2, result.effects().size());
        EffectStyle join = result.effects().get("join");
        assertNull(join.particles(), "the unknown particle is dropped");
        assertNotNull(join.sound(), "the rest of the entry still works");
        assertNotNull(join.title());
        EffectStyle death = result.effects().get("death");
        assertNotNull(death.particles());
        assertEquals(Particle.FLAME, death.particles().particle());
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("[join] unknown particle")));
    }

    @Test void dustNeedsAColour() {
        Map<String, EffectsConfig.EventEffect> defs = new LinkedHashMap<>();
        defs.put("join", valid("DUST"));
        EffectParser.Result result = EffectParser.parse(defs);
        assertNull(result.effects().get("join").particles());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("needs a colour")));

        defs.get("join").particle.colour = "#ff8800";
        result = EffectParser.parse(defs);
        assertNotNull(result.effects().get("join").particles());
        assertNotNull(result.effects().get("join").particles().data().dust());
    }

    @Test void particlesThatNeedBlockOrItemDataAreRejected() {
        for (String name : List.of("BLOCK", "ITEM")) {
            Map<String, EffectsConfig.EventEffect> defs = new LinkedHashMap<>();
            defs.put("death", valid(name));
            EffectParser.Result result = EffectParser.parse(defs);
            assertNull(result.effects().get("death").particles());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("does not support")));
        }
    }

    @Test void unknownEventKeysAreSkippedWithAWarning() {
        Map<String, EffectsConfig.EventEffect> defs = new LinkedHashMap<>();
        defs.put("not-an-event", valid("FLAME"));
        defs.put("level_up", valid("FLAME"));
        EffectParser.Result result = EffectParser.parse(defs);
        assertEquals(1, result.effects().size());
        assertTrue(result.effects().containsKey("level-up"), "underscores are normalised");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Unknown event key")));
    }

    @Test void fireworkFallsBackToWhiteWhenEveryColourIsBroken() {
        Map<String, EffectsConfig.EventEffect> defs = new LinkedHashMap<>();
        EffectsConfig.EventEffect raw = valid("FLAME");
        raw.firework.enabled = true;
        raw.firework.colours = List.of("nope", "#1234567");
        raw.firework.fade = List.of("also-nope");
        defs.put("kill", raw);
        EffectStyle style = EffectParser.parse(defs).effects().get("kill");
        assertEquals(1, style.firework().colours().size());
        assertTrue(style.firework().fade().isEmpty());
    }

    @Test void disabledEntriesDoNotProduceWarnings() {
        Map<String, EffectsConfig.EventEffect> defs = new LinkedHashMap<>();
        EffectsConfig.EventEffect raw = valid("NOT_A_PARTICLE");
        raw.enabled = false;
        defs.put("join", raw);
        EffectParser.Result result = EffectParser.parse(defs);
        assertTrue(result.warnings().isEmpty());
        assertTrue(result.effects().containsKey("join"));
    }

    @Test void killEntityOverridesFallBackToTheGenericKillEffect() {
        Map<String, EffectsConfig.EventEffect> defs = new LinkedHashMap<>();
        defs.put("kill", valid("FLAME"));
        defs.put("kill.zombie", valid("SOUL_FIRE_FLAME"));
        Map<String, EffectStyle> styles = EffectParser.parse(defs).effects();
        assertEquals(Particle.SOUL_FIRE_FLAME,
                EffectKeys.forKill(styles, org.bukkit.entity.EntityType.ZOMBIE, styles.get("kill")).particles().particle());
        assertEquals(Particle.FLAME,
                EffectKeys.forKill(styles, org.bukkit.entity.EntityType.SKELETON, styles.get("kill")).particles().particle());
    }

    @Test void invalidPermissionFallsBackToEveryone() {
        Map<String, EffectsConfig.EventEffect> defs = new LinkedHashMap<>();
        EffectsConfig.EventEffect raw = valid("FLAME");
        raw.permission = "not a permission!";
        defs.put("join", raw);
        EffectStyle style = EffectParser.parse(defs).effects().get("join");
        assertEquals("", style.permission());
        assertTrue(EffectParser.parse(defs).warnings().stream().anyMatch(w -> w.contains("invalid permission")));
    }

    @Test void unknownEntityTypesAreIgnored() {
        Map<String, EffectsConfig.EventEffect> defs = new LinkedHashMap<>();
        EffectsConfig.EventEffect raw = valid("FLAME");
        raw.entityTypes = new java.util.ArrayList<>(List.of("ZOMBIE", "NOT_A_MOB"));
        defs.put("kill", raw);
        EffectStyle style = EffectParser.parse(defs).effects().get("kill");
        assertEquals(1, style.entityTypes().size());
        assertTrue(style.entityTypes().contains(org.bukkit.entity.EntityType.ZOMBIE));
    }

    @Test void namespacedSoundKeysAreAccepted() {
        Map<String, EffectsConfig.EventEffect> defs = new LinkedHashMap<>();
        EffectsConfig.EventEffect raw = valid("FLAME");
        raw.sound.key = "minecraft:entity.player.levelup";
        defs.put("join", raw);
        EffectStyle style = EffectParser.parse(defs).effects().get("join");
        assertEquals("minecraft:entity.player.levelup", style.sound().key());
        assertFalse(style.sound().enumName());
    }
}
