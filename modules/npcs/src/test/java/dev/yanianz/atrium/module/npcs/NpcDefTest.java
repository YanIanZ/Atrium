package dev.yanianz.atrium.module.npcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.yanianz.atrium.api.npcs.NpcType;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NpcDefTest {
    private static StoredLocation at(String world, double x, double y, double z) {
        return new StoredLocation(world, x, y, z, 90f, 0f);
    }

    private static NpcDef sample() {
        return NpcDef.create("Steve", "Steve", at("world", 1, 64, 2), "Notch", NpcType.NATIVE)
                .withPose("crouching")
                .withScale(1.5)
                .withGlow("red")
                .withNameplate(false)
                .withEquipment("HAND", "AAAA")
                .withActions(NpcInteractClick.RIGHT, List.of("warp spawn"))
                .withPermission("atrium.npc.vip")
                .withCooldown(30_000)
                .withCost(new BigDecimal("12.50"))
                .withLook(true, 12);
    }

    @Test void storedPoseIsNormalisedAndUnknownValuesFallBack() {
        assertEquals(NpcPose.SNEAKING, sample().pose());
        assertEquals(NpcPose.STANDING, NpcDef.create("A", "A", at("world", 0, 0, 0), null, NpcType.NATIVE)
                .withPose("magic").pose());
    }

    @Test void scaleIsClampedToTheCommandRange() {
        NpcDef base = NpcDef.create("A", "A", at("world", 0, 0, 0), null, NpcType.NATIVE);
        assertEquals(NpcScale.MIN, base.withScale(0.01).scale(), 1e-9);
        assertEquals(NpcScale.MAX, base.withScale(50).scale(), 1e-9);
    }

    @Test void copyRenamesTheDefaultDisplayNameAndKeepsTheRest() {
        NpcDef source = sample();
        NpcDef copy = source.copyAs("Alex", at("nether", -5, 70, 3));
        assertEquals(0, copy.id());
        assertEquals("Alex", copy.name());
        assertEquals("Alex", copy.displayName());
        assertEquals("nether", copy.world());
        assertEquals(-5, copy.x(), 1e-9);
        assertEquals(90f, copy.yaw(), 1e-9);
        assertEquals(source.skin(), copy.skin());
        assertEquals(source.pose(), copy.pose());
        assertEquals(source.scale(), copy.scale(), 1e-9);
        assertEquals(source.glow(), copy.glow());
        assertEquals(source.nameplate(), copy.nameplate());
        assertEquals(source.equipment(), copy.equipment());
        assertEquals(source.rightActions(), copy.rightActions());
        assertEquals(source.permission(), copy.permission());
        assertEquals(source.cooldownMillis(), copy.cooldownMillis());
        assertEquals(source.cost(), copy.cost());
        assertEquals(source.engine(), copy.engine());
    }

    @Test void copyKeepsACustomDisplayName() {
        NpcDef source = sample().withDisplayName("<gold>Shopkeeper</gold>");
        assertEquals("<gold>Shopkeeper</gold>", source.copyAs("Alex", at("world", 0, 0, 0)).displayName());
    }

    @Test void equipmentIsCopiedImmutably() {
        NpcDef source = sample();
        NpcDef withMore = source.withEquipment("HEAD", "BBBB");
        assertEquals(Map.of("HAND", "AAAA"), source.equipment());
        assertEquals(Map.of("HAND", "AAAA", "HEAD", "BBBB"), withMore.equipment());
        assertNull(source.withEquipment("HAND", null).equipment().get("HAND"));
    }
}
