package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Every EssentialsX parser against files in both formats: {@code fixtures/essentials-2.22/} holds files that
 * EssentialsX 2.22.0 itself loaded and saved again (userdata of Alice and Bob were rewritten by {@code /eco give}
 * and {@code /mail send}), the {@code *-legacy*} fixtures the layouts before Configurate (2.18 and older).
 */
class EssentialsFormatsTest {
    private static final UUID WORLD = UUID.fromString("89050808-64e9-4f0b-bfaf-883cebd1ad0c");
    private static final EssentialsLocations.WorldIndex WORLDS =
            new EssentialsLocations.WorldIndex(Map.of(WORLD, "world"), Set.of("world", "world_nether"));
    private static final String ALICE = "10920508-d5d8-3eed-93d2-92f193afe7d7.yml";
    private static final String BOB = "faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b.yml";
    private static final String CAROL = "0af3f783-cbb9-32f0-953c-0d7e29e82d58.yml";

    private static Path path(String name) throws Exception {
        return Path.of(EssentialsFormatsTest.class.getResource("/fixtures/" + name).toURI());
    }

    private static ConfigurationNode yaml(String name) throws Exception {
        return YamlConfigurationLoader.builder().path(path(name)).build().load();
    }

    private static EssentialsUserParser.UserFile user(String file) throws Exception {
        return EssentialsUserParser.parse(file, yaml("essentials-2.22/userdata/" + file));
    }

    // ---- userdata, EssentialsX 2.19+ ----

    @Test
    void userWrittenByEssentialsX() throws Exception {
        EssentialsUserParser.UserFile alice = user(ALICE);
        assertEquals(UUID.fromString("10920508-d5d8-3eed-93d2-92f193afe7d7"), alice.uuid());
        assertEquals("Alice", alice.label(), "last-account-name (dashed Configurate key)");
        assertEquals(new BigDecimal("1234.56"), alice.money(), "money is a quoted BigDecimal string");
        assertEquals(1, alice.homes().size());
        StoredLocation home = WORLDS.resolve(alice.homes().getFirst().location()).orElseThrow();
        assertEquals(new StoredLocation("world", -30.5, -60, 12.5, 0f, 0f), home);
        assertNotNull(alice.lastLocation(), "lastlocation");
        assertNotNull(alice.logoutLocation(), "logoutlocation");
        assertEquals(1762000000000L, alice.logout());
        assertEquals(List.of("faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b"), alice.ignores());
        assertEquals(2, alice.mails().size());
        EssentialsUserParser.ParsedMail console = alice.mails().get(0);
        assertEquals("Console", console.senderName());
        assertNull(console.sender(), "sender-uuid 'null' is no UUID");
        assertEquals("Hello from console", console.message());
        EssentialsUserParser.ParsedMail welcome = alice.mails().get(1);
        assertEquals("AdminSteve", welcome.senderName());
        assertEquals(UUID.fromString("c1095cb6-a004-33af-aeef-3d18c03a563c"), welcome.sender());
        assertEquals(1761980000000L, welcome.created());
        assertFalse(alice.states().muted());
    }

    @Test
    void muteAndJailStatesWrittenByEssentialsX() throws Exception {
        EssentialsUserParser.UserFile bob = user(BOB);
        assertEquals(new BigDecimal("109.75"), bob.money());
        assertEquals("Bobby", bob.nickname());
        assertTrue(bob.states().muted());
        assertEquals("spam", bob.states().muteReason(), "mute-reason");
        assertEquals(4102444800000L, bob.states().muteUntil(), "timestamps.mute");

        EssentialsUserParser.UserFile carol = user(CAROL);
        assertTrue(carol.states().jailed());
        assertEquals("cell", carol.states().jail());
        assertEquals(3, carol.homes().size());
        assertEquals(new BigDecimal("0"), carol.money());
    }

    // ---- userdata, old formats ----

    @Test
    void legacyUserdata() throws Exception {
        EssentialsUserParser.UserFile old = EssentialsUserParser.parse("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee.yml",
                yaml("essentials-user-legacy.yml"));
        assertEquals("OldTimer", old.label());
        assertEquals(0, new BigDecimal("0.3").compareTo(old.money()), "a double balance keeps its meaningful digits");
        assertEquals(Set.of("home", "world_nether"),
                Set.copyOf(old.homes().stream().map(EssentialsUserParser.RawHome::name).toList()),
                "home.default world becomes 'home', the others are named after their world");
        assertEquals(List.of("Notch", "jeb_"), old.mails().stream().map(EssentialsUserParser.ParsedMail::senderName).toList());
        assertEquals("hi there", old.mails().get(1).message());
        assertEquals(List.of("Griefer"), old.ignores(), "names are resolved by the job");
        assertTrue(old.states().muted());
        assertEquals("caps", old.states().muteReason());
        assertNotNull(old.lastLocation());
    }

    @Test
    void legacyMailWrappedByTheUpgrade() throws Exception {
        EssentialsUserParser.UserFile user = EssentialsUserParser.parse("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee.yml",
                yaml("essentials-user-legacy-mail.yml"));
        assertEquals(1, user.mails().size());
        assertEquals("Notch", user.mails().getFirst().senderName());
        assertEquals("welcome back", user.mails().getFirst().message());
        assertTrue(user.mails().getFirst().read());
    }

    // ---- warps, jails, spawn ----

    @Test
    void warpFileWrittenByEssentialsX() throws Exception {
        List<EssentialsWarpParser.ParsedWarp> shop = EssentialsWarpParser.parse("shop.yml", yaml("essentials-2.22/warps/shop.yml"));
        assertEquals(1, shop.size());
        assertEquals("Shop", shop.getFirst().name(), "the name: key keeps the case /setwarp was typed with");
        assertEquals(UUID.fromString("c1095cb6-a004-33af-aeef-3d18c03a563c"), shop.getFirst().owner());
        assertEquals(new StoredLocation("world", 100.5, -60, 100.5, 180f, 0f),
                WORLDS.resolve(shop.getFirst().location()).orElseThrow());
    }

    @Test
    void jailFileWrittenByEssentialsX() throws Exception {
        List<EssentialsJailParser.ParsedJail> jails = EssentialsJailParser.parse(yaml("essentials-2.22/jail.yml"));
        assertEquals(1, jails.size());
        assertEquals("cell", jails.getFirst().name());
        assertEquals("world", WORLDS.resolve(jails.getFirst().location()).orElseThrow().world());
    }

    @Test
    void unknownWorldUuidFallsBackToTheWorldName() throws Exception {
        // The same data copied to a server whose world folder got a new UUID (or an AWF world not loaded yet).
        EssentialsLocations.WorldIndex other = new EssentialsLocations.WorldIndex(Map.of(), Set.of());
        StoredLocation home = other.resolve(user(ALICE).homes().getFirst().location()).orElseThrow();
        assertEquals("world", home.world());
    }

    // ---- kits ----

    @Test
    void kitsFromKitsYmlAndTheKitsFolder() throws Exception {
        EssentialsItemDb db = EssentialsItemDb.parse(Files.readString(path("essentials-2.22/items.json")), null);
        List<EssentialsKitsParser.ParsedKit> main = EssentialsKitsParser.parse(yaml("essentials-2.22/kits.yml"), "$");
        EssentialsKitsParser.ParsedKit tools = main.stream().filter(k -> k.name().equals("tools")).findFirst().orElseThrow();
        assertEquals(10, tools.delaySeconds());
        ExtendedImportJob.KitRecord record = EssentialsImportJob.kitRecord(tools, null, db);
        assertEquals("stone_sword", record.items().getFirst().material(), "items.json alias 'stonesword'");

        EssentialsKitsParser.ParsedKit starter = main.stream().filter(k -> k.name().equals("starter")).findFirst().orElseThrow();
        assertEquals(2, starter.delaySeconds(), "delay 1.5 s rounds up instead of becoming 0");
        assertEquals(0, starter.specs().getFirst().slot(), "slot:0 prefix");
        assertEquals("stone_sword", starter.specs().getFirst().material());
        assertEquals(2, starter.commands().size(), "$50 and the /say line");

        List<EssentialsKitsParser.ParsedKit> folder = EssentialsKitsParser.parse(yaml("essentials-2.22/kits/vip.yml"));
        assertEquals("vip", folder.getFirst().name());
        assertTrue(folder.getFirst().oneTime(), "delay -1 = once per player");
    }

    @Test
    void itemDbResolvesAliasesAndKeepsMaterialNames() throws Exception {
        EssentialsItemDb db = EssentialsItemDb.parse(Files.readString(path("essentials-2.22/items.json")), null);
        assertEquals("diamond_pickaxe", db.material("dpickaxe"));
        assertEquals("leather_helmet", db.material("LHELM"));
        assertEquals("diamond_sword", db.material("diamond_sword"), "unknown names pass through");
        assertEquals("stone_sword", db.material("minecraft:stone_sword"));
    }

    @Test
    void serializedKitItemWithLineBreaksAndCurrencySymbol() {
        String base64 = Base64.getMimeEncoder().encodeToString(new byte[120]);
        assertTrue(base64.contains("\r\n"), "Essentials writes the serialized item in 76 character lines");
        EssentialsItemParser.Line line = EssentialsItemParser.parse("@" + base64, -1);
        assertNotNull(line.item().serialized());
        assertEquals(EssentialsItemParser.Kind.MONEY, EssentialsItemParser.parse("€25", -1, "€").kind());
        assertEquals(EssentialsItemParser.Kind.MONEY, EssentialsItemParser.parse("$25", -1, "€").kind());
    }

    @Test
    void essentialsMoneyRoundsDoublesInsteadOfRejectingThem() {
        assertEquals(0, new BigDecimal("0.3").compareTo(EssentialsMoney.parse("0.30000000000000004").orElseThrow()));
        assertEquals(new BigDecimal("1234.56"), EssentialsMoney.parse("1234.56").orElseThrow());
        assertEquals(0, new BigDecimal("1000").compareTo(EssentialsMoney.parse("1E+3").orElseThrow()));
    }
}
