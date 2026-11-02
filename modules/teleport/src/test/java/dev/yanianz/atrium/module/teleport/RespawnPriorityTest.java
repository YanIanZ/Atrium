package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RespawnPriorityTest {
    @Test
    void worldEntryWinsOverTheStarEntry() {
        Map<String, List<String>> orders = new LinkedHashMap<>();
        orders.put("*", List.of("spawn", "worldspawn"));
        orders.put("world_nether", List.of("anchor", "bed"));
        assertEquals(List.of("anchor", "bed"), RespawnPriority.orderFor(orders, "world_nether"));
        assertEquals(List.of("spawn", "worldspawn"), RespawnPriority.orderFor(orders, "world"));
    }

    @Test
    void missingConfigurationFallsBackToTheBuiltInOrder() {
        assertEquals(List.of("anchor", "bed", "spawn", "home", "worldspawn"),
                RespawnPriority.orderFor(Map.of(), "world"));
        assertEquals(List.of("anchor", "bed", "spawn", "home", "worldspawn"),
                RespawnPriority.orderFor(null, "world"));
        Map<String, List<String>> broken = new LinkedHashMap<>();
        broken.put("*", List.of());
        assertEquals(List.of("anchor", "bed", "spawn", "home", "worldspawn"),
                RespawnPriority.orderFor(broken, "world"));
    }

    @Test
    void theFirstAvailableTokenWins() {
        List<String> order = List.of("anchor", "bed", "spawn", "worldspawn");
        assertEquals(Optional.of("bed"), RespawnPriority.choose(order, token -> !token.equals("anchor")));
        assertEquals(Optional.of("anchor"), RespawnPriority.choose(order, token -> true));
        assertEquals(Optional.empty(), RespawnPriority.choose(order, token -> false));
    }

    @Test
    void tokensAreTrimmedAndLowercase() {
        assertEquals(List.of("anchor", "bed"), RespawnPriority.normalise(List.of(" ANCHOR ", "Bed", "", " ")));
        assertEquals(Optional.of("bed"), RespawnPriority.choose(List.of("ANCHOR", "BED"), token -> token.equals("bed")));
    }

    @Test
    void warpTokensCarryTheWarpName() {
        assertEquals(Optional.of("lobby"), RespawnPriority.warpName("warp:lobby"));
        assertEquals(Optional.of("Old Spawn"), RespawnPriority.warpName("WARP: Old Spawn"));
        assertTrue(RespawnPriority.warpName("warp:").isEmpty());
        assertTrue(RespawnPriority.warpName("spawn").isEmpty());
        assertTrue(RespawnPriority.warpName(null).isEmpty());
    }

    @Test
    void unknownTokensAreNeverAvailable() {
        List<String> order = List.of("typo", "worldspawn");
        assertEquals(Optional.of("worldspawn"),
                RespawnPriority.choose(order, token -> token.equals("worldspawn")));
        assertFalse(RespawnPriority.choose(order, token -> false).isPresent());
    }
}
