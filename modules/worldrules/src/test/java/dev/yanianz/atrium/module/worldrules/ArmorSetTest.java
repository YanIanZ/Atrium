package dev.yanianz.atrium.module.worldrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class ArmorSetTest {
    private static ArmorSet.CompiledSet set(int required, Map<ArmorSet.Slot, ArmorSet.CompiledPiece> pieces) {
        return new ArmorSet.CompiledSet("test", required, pieces, List.of());
    }

    private static Map<ArmorSet.Slot, ArmorSet.CompiledPiece> diamondSet() {
        Map<ArmorSet.Slot, ArmorSet.CompiledPiece> pieces = new EnumMap<>(ArmorSet.Slot.class);
        pieces.put(ArmorSet.Slot.BOOTS, piece(Material.DIAMOND_BOOTS));
        pieces.put(ArmorSet.Slot.LEGGINGS, piece(Material.DIAMOND_LEGGINGS));
        pieces.put(ArmorSet.Slot.CHESTPLATE, piece(Material.DIAMOND_CHESTPLATE));
        pieces.put(ArmorSet.Slot.HELMET, piece(Material.DIAMOND_HELMET));
        return pieces;
    }

    private static ArmorSet.CompiledPiece piece(Material material) {
        return new ArmorSet.CompiledPiece(Set.of(material), 0);
    }

    private static Material[] materials(Material boots, Material leggings, Material chest, Material helmet) {
        return new Material[] {boots, leggings, chest, helmet};
    }

    private static int[] noModelData() {
        return new int[] {-1, -1, -1, -1};
    }

    @Test
    void completeSetMatchesAndMissingPieceDoesNot() {
        ArmorSet.CompiledSet set = set(4, diamondSet());
        assertTrue(set.matches(materials(Material.DIAMOND_BOOTS, Material.DIAMOND_LEGGINGS,
                Material.DIAMOND_CHESTPLATE, Material.DIAMOND_HELMET), noModelData()));
        assertFalse(set.matches(materials(Material.DIAMOND_BOOTS, Material.DIAMOND_LEGGINGS,
                Material.DIAMOND_CHESTPLATE, Material.IRON_HELMET), noModelData()));
        assertFalse(set.matches(materials(Material.DIAMOND_BOOTS, Material.DIAMOND_LEGGINGS,
                Material.DIAMOND_CHESTPLATE, Material.AIR), noModelData()));
    }

    @Test
    void partialSetsRequireTheirConfiguredNumberOfPieces() {
        Map<ArmorSet.Slot, ArmorSet.CompiledPiece> pieces = new EnumMap<>(ArmorSet.Slot.class);
        pieces.put(ArmorSet.Slot.HELMET, piece(Material.GOLDEN_HELMET));
        pieces.put(ArmorSet.Slot.BOOTS, piece(Material.GOLDEN_BOOTS));
        ArmorSet.CompiledSet set = set(2, pieces);
        assertTrue(set.matches(materials(Material.GOLDEN_BOOTS, null, null, Material.GOLDEN_HELMET), noModelData()));
        assertFalse(set.matches(materials(Material.GOLDEN_BOOTS, null, null, Material.IRON_HELMET), noModelData()));
    }

    @Test
    void customModelDataMustMatchWhenConfigured() {
        ArmorSet.CompiledPiece piece = new ArmorSet.CompiledPiece(Set.of(Material.LEATHER_HELMET), 7);
        assertTrue(piece.matches(Material.LEATHER_HELMET, 7));
        assertFalse(piece.matches(Material.LEATHER_HELMET, 8));
        assertFalse(piece.matches(Material.LEATHER_HELMET, -1));
        ArmorSet.CompiledPiece any = new ArmorSet.CompiledPiece(Set.of(Material.LEATHER_HELMET), 0);
        assertTrue(any.matches(Material.LEATHER_HELMET, 123));
    }

    @Test
    void emptyMaterialListAcceptsAnyNonAirItem() {
        ArmorSet.CompiledPiece any = new ArmorSet.CompiledPiece(Set.of(), 0);
        assertTrue(any.matches(Material.NETHERITE_HELMET, -1));
        assertFalse(any.matches(Material.AIR, -1));
    }

    @Test
    void slotNamesAcceptCommonAliases() {
        assertEquals(ArmorSet.Slot.HELMET, ArmorSet.Slot.parse("head"));
        assertEquals(ArmorSet.Slot.CHESTPLATE, ArmorSet.Slot.parse("Chest"));
        assertEquals(ArmorSet.Slot.LEGGINGS, ArmorSet.Slot.parse("legs"));
        assertEquals(ArmorSet.Slot.BOOTS, ArmorSet.Slot.parse("feet"));
    }
}
