package dev.yanianz.atrium.module.warps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.module.warps.WarpSuggestions.Viewer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class WarpSuggestionsTest {
    private static final StoredLocation AT = new StoredLocation("world", 0.5, 64, 0.5, 0, 0);
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private static Warp warp(String name, String category, UUID creator) {
        return new Warp(name, category, AT, creator, 0L);
    }

    private static Warp warp(String name, String category, UUID creator, String permission) {
        return new Warp(name, category, AT, creator, 0L, permission);
    }

    private static Viewer player(UUID id, String... permissions) {
        Set<String> granted = Set.of(permissions);
        return new Viewer(id, granted::contains);
    }

    private static final Viewer CONSOLE = new Viewer(null, permission -> true);

    private final List<Warp> warps = List.of(
            warp("Spawn", "general", null),
            warp("shop", "Trade", null),
            warp("Shrine", "trade", null),
            warp("VipLounge", "vip", null, "group.vip"),
            warp("staffroom", "staff", null),
            warp("alice_base", "homes", ALICE));

    // ---- /warp <name> ----

    @Test
    void usableFiltersByTypedPrefixIgnoringCase() {
        Viewer viewer = player(BOB);
        assertEquals(List.of("shop", "Shrine", "Spawn", "staffroom"), WarpSuggestions.usable(warps, viewer, false, "s"));
        assertEquals(List.of("shop", "Shrine"), WarpSuggestions.usable(warps, viewer, false, "SH"));
        assertEquals(List.of("Spawn"), WarpSuggestions.usable(warps, viewer, false, "Sp"));
        assertEquals(6, WarpSuggestions.usable(warps, viewer, false, "").size());
    }

    @Test
    void usableHidesForbiddenWarpsButShowsEveryUsableOne() {
        Viewer bob = player(BOB, "atrium.warp.spawn", "group.vip");
        assertEquals(List.of("Spawn", "VipLounge"), WarpSuggestions.usable(warps, bob, true, ""));
        Viewer alice = player(ALICE);
        assertEquals(List.of("alice_base"), WarpSuggestions.usable(warps, alice, true, ""),
                "a player always sees the warp they own");
        Viewer staff = player(UUID.randomUUID(), WarpsModule.PERM_ALL);
        assertEquals(6, WarpSuggestions.usable(warps, staff, true, "").size());
        assertEquals(6, WarpSuggestions.usable(warps, CONSOLE, true, "").size(), "console sees every warp");
    }

    // ---- /setwarp /delwarp /renamewarp ----

    @Test
    void manageableUsesTheCommandsOwnAdminPermission() {
        Viewer setOnly = player(UUID.randomUUID(), WarpsModule.PERM_SETWARP);
        Viewer delOnly = player(UUID.randomUUID(), WarpsModule.PERM_DELWARP);
        assertEquals(6, WarpSuggestions.manageable(warps, setOnly, WarpsModule.PERM_SETWARP, "").size());
        assertEquals(List.of(), WarpSuggestions.manageable(warps, setOnly, WarpsModule.PERM_DELWARP, ""),
                "/delwarp must not offer warps that atrium.setwarp alone cannot delete");
        assertEquals(List.of(), WarpSuggestions.manageable(warps, delOnly, WarpsModule.PERM_SETWARP, ""),
                "/setwarp and /renamewarp must not offer warps that atrium.delwarp alone cannot change");
        assertEquals(6, WarpSuggestions.manageable(warps, delOnly, WarpsModule.PERM_DELWARP, "").size());
    }

    @Test
    void manageableShowsOwnWarpsToCreatorsAndAllToConsole() {
        Viewer alice = player(ALICE, WarpsModule.PERM_CREATE);
        assertEquals(List.of("alice_base"), WarpSuggestions.manageable(warps, alice, WarpsModule.PERM_DELWARP, "A"));
        assertEquals(List.of(), WarpSuggestions.manageable(warps, player(ALICE), WarpsModule.PERM_DELWARP, ""),
                "owning a warp without atrium.warps.create does not allow changing it");
        assertEquals(6, WarpSuggestions.manageable(warps, CONSOLE, WarpsModule.PERM_DELWARP, "").size());
    }

    // ---- /warps <category>, /warpgroups <group> ----

    @Test
    void groupsOnlyListCategoriesWithAUsableWarpOnceEach() {
        Viewer bob = player(BOB, "atrium.warp.spawn", "atrium.warp.shop", "atrium.warp.shrine");
        assertEquals(List.of("general", "Trade"), WarpSuggestions.groups(warps, bob, true, ""),
                "hidden categories (vip, staff, homes) must not leak; Trade/trade is one group");
        assertEquals(List.of("Trade"), WarpSuggestions.groups(warps, bob, true, "TR"));
        assertEquals(List.of("general", "homes", "staff", "Trade", "vip"),
                WarpSuggestions.groups(warps, CONSOLE, true, ""));
    }

    @Test
    void categoryArgumentOffersExistingCategoriesAndTheDefault() {
        assertEquals(List.of("general"), WarpSuggestions.categories(List.of(), "general", ""),
                "the default category is offered even before any warp exists");
        assertEquals(List.of("general", "homes", "staff", "Trade", "vip"),
                WarpSuggestions.categories(warps, "general", ""));
        assertEquals(List.of("Trade"), WarpSuggestions.categories(warps, "general", "t"));
    }

    // ---- through Brigadier, like the client asks ----

    @Test
    void brigadierSuggestsAndEverySuggestionParsesBack() throws CommandSyntaxException {
        Map<String, Warp> live = new ConcurrentHashMap<>();
        warps.forEach(w -> live.put(w.key(), w));
        CommandDispatcher<Viewer> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.<Viewer>literal("warp")
                .then(RequiredArgumentBuilder.<Viewer, String>argument("name", StringArgumentType.word())
                        .suggests((c, builder) -> {
                            WarpSuggestions.usable(live.values(), c.getSource(), true, builder.getRemaining())
                                    .forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(c -> 1)));
        Viewer bob = player(BOB, "atrium.warp.spawn", "atrium.warp.shop", "atrium.warp.shrine");

        assertEquals(List.of("shop", "Shrine", "Spawn"), suggest(dispatcher, "warp ", bob));
        assertEquals(List.of("shop", "Shrine"), suggest(dispatcher, "warp SH", bob));
        for (String name : suggest(dispatcher, "warp ", CONSOLE)) {
            assertEquals(name, StringArgumentType.word().parse(new StringReader(name)),
                    "every suggested name must be typeable as a word argument");
            assertEquals(1, dispatcher.execute("warp " + name, CONSOLE));
        }

        // Suggestions follow changes immediately: no cache between the warp map and the provider.
        live.remove("shop");
        live.put("shopping", warp("Shopping", "trade", null));
        assertEquals(List.of("Shopping"), suggest(dispatcher, "warp shop", CONSOLE));
    }

    @Test
    void suggestionsFollowManagerChangesImmediately() {
        WarpManager manager = new WarpManager(new MemoryStorage(), e -> true, e -> true);
        manager.set(null, "Alpha", "general", AT, null).join();
        assertEquals(List.of("Alpha"), WarpSuggestions.usable(manager.all(), CONSOLE, false, "a"));
        manager.rename(null, "alpha", "Atlas").join();
        assertEquals(List.of("Atlas"), WarpSuggestions.usable(manager.all(), CONSOLE, false, "a"));
        manager.delete(null, "atlas").join();
        assertTrue(WarpSuggestions.usable(manager.all(), CONSOLE, false, "a").isEmpty());
    }

    private static List<String> suggest(CommandDispatcher<Viewer> dispatcher, String input, Viewer viewer) {
        List<String> out = new ArrayList<>();
        for (Suggestion suggestion : dispatcher.getCompletionSuggestions(dispatcher.parse(input, viewer)).join().getList()) {
            out.add(suggestion.getText());
        }
        return out;
    }

    private static final class MemoryStorage implements WarpStorage {
        private final Map<String, Warp> rows = new ConcurrentHashMap<>();

        @Override
        public CompletableFuture<List<Warp>> loadAll() {
            return CompletableFuture.completedFuture(List.copyOf(rows.values()));
        }

        @Override
        public CompletableFuture<Void> upsert(Warp warp) {
            rows.put(warp.key(), warp);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Boolean> delete(String key) {
            return CompletableFuture.completedFuture(rows.remove(key) != null);
        }

        @Override
        public CompletableFuture<Boolean> rename(String oldKey, Warp renamed) {
            rows.remove(oldKey);
            rows.put(renamed.key(), renamed);
            return CompletableFuture.completedFuture(true);
        }
    }
}
