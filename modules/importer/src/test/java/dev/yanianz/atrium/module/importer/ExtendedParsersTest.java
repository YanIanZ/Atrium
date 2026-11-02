package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.moderation.PunishmentType;
import dev.yanianz.atrium.api.teleport.SpawnAdminService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** Parsers of the spawn, jail, kit, punishment and mail categories, against synthetic fixtures. */
class ExtendedParsersTest {
    private static final UUID ALEX = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private static ConfigurationNode fixture(String name) throws Exception {
        Path path = Path.of(ExtendedParsersTest.class.getResource("/fixtures/" + name).toURI());
        return YamlConfigurationLoader.builder().path(path).build().load();
    }

    // ---- spawn ----

    @Test
    void cmiSpawnReadsMainAndFirstJoinSpawnAndNewbieKit() throws Exception {
        CmiSpawnParser.Result result = CmiSpawnParser.parse(fixture("cmi-config-spawn.yml"));
        assertEquals(2, result.spawns().size());
        CmiSpawnParser.ParsedSpawn main = result.spawns().get(0);
        assertTrue(main.ok());
        assertEquals(SpawnAdminService.Kind.DEFAULT, main.kind());
        assertEquals("world", main.location().world());
        assertEquals(0.5, main.location().x());
        assertEquals(90f, main.location().yaw());
        assertEquals(10f, main.location().pitch());
        CmiSpawnParser.ParsedSpawn first = result.spawns().get(1);
        assertEquals(SpawnAdminService.Kind.FIRST_JOIN, first.kind());
        assertEquals(100.5, first.location().x());
        assertEquals(-50.5, first.location().z());
        assertEquals("starter", result.firstJoinKit());
    }

    @Test
    void cmiSpawnWithoutSpawnSectionIsEmpty() throws Exception {
        CmiSpawnParser.Result result = CmiSpawnParser.parse(fixture("cmi-config-sqlite.yml"));
        assertTrue(result.spawns().isEmpty());
        assertNull(CmiSpawnParser.parse(null).firstJoinKit());
    }

    // ---- jails ----

    @Test
    void cmiJailUsesFirstCellAndCoversTheArea() throws Exception {
        List<CmiJailParser.ParsedJail> jails = CmiJailParser.parse(fixture("cmi-jails.yml"));
        assertEquals(3, jails.size());

        CmiJailParser.ParsedJail town = jails.get(0);
        assertTrue(town.ok());
        assertEquals("town", town.name());
        assertEquals(0.5, town.spawn().x());
        assertEquals(61.0, town.spawn().y());
        assertEquals(2, town.cells());
        // Farthest corner from (0.5, 61, 0.5): dx 10.5, dy 19, dz 10.5 -> sqrt(581.5) = 24.1 -> 25.
        assertEquals(25.0, town.radius());
        assertNotNull(town.release());
        assertEquals(20.5, town.release().z());
        assertTrue(town.notes().stream().anyMatch(n -> n.contains("extra cell")));

        CmiJailParser.ParsedJail renamed = jails.get(1);
        assertTrue(renamed.ok());
        assertEquals("old_jail", renamed.name());
        assertEquals("world_nether", renamed.spawn().world());
        assertEquals(2.0, renamed.spawn().x());
        assertTrue(renamed.notes().stream().anyMatch(n -> n.contains("renamed")));

        assertFalse(jails.get(2).ok());
    }

    @Test
    void cmiJailAreaKeepsEscapedWorldNames() {
        CmiJailParser.Area area = CmiJailParser.area("myT8Cworld:5:1:5:-5:9:-5").orElseThrow();
        assertEquals("my:world", area.world());
        assertEquals(-5.0, area.minX());
        assertEquals(9.0, area.maxY());
        assertTrue(CmiJailParser.area("world:1:2:3").isEmpty());
    }

    @Test
    void essentialsJailsResolveUuidWorldsAndReportBrokenOnes() throws Exception {
        List<EssentialsJailParser.ParsedJail> jails = EssentialsJailParser.parse(fixture("essentials-jails.yml"));
        assertEquals(3, jails.size());
        EssentialsJailParser.ParsedJail cell = jails.get(0);
        assertTrue(cell.ok());
        assertEquals("cell1", cell.name());
        assertEquals("world", cell.location().worldName());
        assertEquals(10.5, cell.location().x());
        assertEquals(180f, cell.location().yaw());

        EssentialsJailParser.ParsedJail renamed = jails.get(1);
        assertTrue(renamed.ok());
        assertEquals("bad_jail", renamed.name());
        assertFalse(renamed.notes().isEmpty());

        assertFalse(jails.get(2).ok());
    }

    // ---- kits ----

    @Test
    void cmiKitsReadItemsCostUsesAndUnsupportedSettings() throws Exception {
        List<CmiKitParser.ParsedKit> kits = CmiKitParser.parse(fixture("cmi-kits.yml"));
        assertEquals(2, kits.size());

        CmiKitParser.ParsedKit starter = kits.get(0);
        assertTrue(starter.ok());
        assertEquals("starter", starter.name());
        assertEquals("Starter", starter.sourceName());
        assertEquals(3600, starter.delaySeconds());
        assertEquals(0, new BigDecimal("100.5").compareTo(starter.cost()));
        assertEquals(5, starter.maxUses());
        assertTrue(starter.notes().stream().anyMatch(n -> n.startsWith("Commands")));
        assertEquals(3, starter.items().size());

        KitItemSpec sword = starter.items().get(0);
        assertEquals(0, sword.slot(), "CMI slots are 1-based");
        assertEquals("stone_sword", sword.material());
        assertEquals("&bTrusty Sword", sword.name());
        assertEquals(List.of("First line", "Second line"), sword.lore());
        assertEquals(Map.of("SHARPNESS", 2), sword.enchantments());
        assertEquals(List.of("meta.custom-model-data"), sword.unsupported());
        assertEquals(16, starter.items().get(1).amount());
        assertEquals(36, starter.items().get(2).slot());

        CmiKitParser.ParsedKit vip = kits.get(1);
        assertEquals("vip_kit", vip.name());
        assertEquals(BigDecimal.ZERO, vip.cost());
        assertEquals(-1, vip.maxUses());
        assertTrue(vip.notes().stream().anyMatch(n -> n.contains("disabled")));
        assertTrue(vip.notes().stream().anyMatch(n -> n.contains("negative")));
        assertEquals(2, vip.items().size());
        assertEquals(-1, vip.items().get(0).slot());
        assertEquals(3, vip.items().get(0).amount());
        assertFalse(vip.items().get(1).ok());
    }

    @Test
    void essentialsItemLinesKeepNameLoreAndEnchantments() {
        EssentialsItemParser.Line sword = EssentialsItemParser.parse(
                "diamond_sword 1 name:&bExcalibur lore:First_line|Second_line sharpness:5 unbreaking:3", 4);
        assertEquals(EssentialsItemParser.Kind.ITEM, sword.kind());
        KitItemSpec item = sword.item();
        assertTrue(item.ok());
        assertEquals(4, item.slot());
        assertEquals("diamond_sword", item.material());
        assertEquals(1, item.amount());
        assertEquals("&bExcalibur", item.name());
        assertEquals(List.of("First line", "Second line"), item.lore());
        assertEquals(Map.of("sharpness", 5, "unbreaking", 3), item.enchantments());
        assertTrue(item.unsupported().isEmpty());

        KitItemSpec wool = EssentialsItemParser.parse("wool:14 2 color:red", -1).item();
        assertEquals("wool", wool.material());
        assertEquals(2, wool.amount());
        assertEquals(2, wool.unsupported().size(), "legacy data value and colour are reported");

        assertFalse(EssentialsItemParser.parse("276 1", -1).item().ok());
        assertEquals(64, EssentialsItemParser.parse("stone 64", -1).item().amount());
        assertEquals(99, EssentialsItemParser.parse("stone 500", -1).item().amount());
        assertEquals(EssentialsItemParser.Kind.COMMAND, EssentialsItemParser.parse("/give {player} cake", -1).kind());
        assertEquals(EssentialsItemParser.Kind.MONEY, EssentialsItemParser.parse("$100", -1).kind());

        byte[] bytes = {1, 2, 3};
        KitItemSpec serialized = EssentialsItemParser.parse("@" + java.util.Base64.getEncoder().encodeToString(bytes), -1).item();
        assertTrue(serialized.ok());
        assertEquals(3, serialized.serialized().length);
        assertFalse(EssentialsItemParser.parse("@not base64!", -1).item().ok());
    }

    @Test
    void essentialsKitsBecomeKitRecords() throws Exception {
        List<EssentialsKitsParser.ParsedKit> kits = EssentialsKitsParser.parse(fixture("essentials-kits-items.yml"));
        assertEquals(2, kits.size());
        EssentialsKitsParser.ParsedKit tools = kits.get(0);
        assertTrue(tools.oneTime(), "delay -1 means one use");
        assertEquals(0, tools.delaySeconds());
        assertEquals(4, tools.specs().size());
        assertEquals(2, tools.commands().size());

        ExtendedImportJob.KitRecord record = EssentialsImportJob.kitRecord(tools, null);
        assertNull(record.problem());
        assertEquals("tools", record.name());
        assertTrue(record.oneTime());
        assertTrue(record.notes().stream().anyMatch(n -> n.contains("2 command/money line(s)")));

        EssentialsKitsParser.ParsedKit daily = kits.get(1);
        assertEquals("Daily_Kit", daily.name());
        assertEquals("Daily Kit", daily.sourceName());
        assertEquals(86400, daily.delaySeconds());
        assertEquals("daily_kit", EssentialsImportJob.kitRecord(daily, null).name());
    }

    // ---- punishments and mail ----

    @Test
    void essentialsUserStatesBecomeActivePunishments() throws Exception {
        EssentialsUserParser.UserFile user = EssentialsUserParser.parse(ALEX + ".yml", fixture("essentials-user-states.yml"));
        EssentialsUserParser.States states = user.states();
        assertTrue(states.muted());
        assertEquals(4102444800000L, states.muteUntil());
        assertEquals("spam", states.muteReason());
        assertTrue(states.jailed());
        assertEquals("cell1", states.jail());
        assertTrue(states.banned());
        assertEquals("griefing", states.banReason());
        assertNotNull(user.logoutLocation());

        List<ExtendedImportJob.PunishmentRecord> records = EssentialsImportJob.punishmentsOf(ALEX, user);
        assertEquals(3, records.size());
        assertEquals(PunishmentType.TEMPMUTE, records.get(0).type());
        assertEquals(Instant.ofEpochMilli(4102444800000L), records.get(0).expires());
        assertEquals(PunishmentType.JAIL, records.get(1).type());
        assertEquals("cell1", records.get(1).jail());
        assertEquals(PunishmentType.TEMPBAN, records.get(2).type());
        assertEquals("griefing", records.get(2).reason());
        assertEquals(Instant.ofEpochMilli(1600000100000L), records.get(2).created(), "logout time is the best creation time");
        records.forEach(r -> assertEquals(ALEX, r.target()));
    }

    @Test
    void punishmentWithoutAnySessionTimeIsDatedNow() throws Exception {
        ConfigurationNode root = YamlConfigurationLoader.builder().buildAndLoadString("""
                lastAccountName: Bob
                ban:
                  reason: griefing
                """);
        EssentialsUserParser.UserFile user = EssentialsUserParser.parse(ALEX + ".yml", root);
        Instant before = Instant.now();
        List<ExtendedImportJob.PunishmentRecord> records = EssentialsImportJob.punishmentsOf(ALEX, user);
        assertEquals(1, records.size());
        assertEquals(PunishmentType.BAN, records.getFirst().type());
        assertNull(records.getFirst().expires());
        assertFalse(records.getFirst().created().isBefore(before), "not 1970");
    }

    @Test
    void essentialsMailInJsonAndLegacyText() throws Exception {
        EssentialsUserParser.UserFile user = EssentialsUserParser.parse(ALEX + ".yml", fixture("essentials-user-states.yml"));
        List<EssentialsUserParser.ParsedMail> mail = user.mails();
        assertEquals(3, mail.size(), "the empty line is dropped");
        EssentialsUserParser.ParsedMail json = mail.get(0);
        assertEquals("Steve", json.senderName());
        assertEquals(UUID.fromString("11111111-2222-3333-4444-555555555555"), json.sender());
        assertEquals("Hello there", json.message());
        assertEquals(1600000000000L, json.created());
        assertTrue(json.read());
        assertEquals("Console", mail.get(1).senderName());
        assertEquals("Server restart tonight", mail.get(1).message());
        assertEquals("Unknown", mail.get(2).senderName());
        assertEquals("just text", mail.get(2).message());
    }

    @Test
    void punishmentLedgerKeysGroupKinds() {
        UUID target = UUID.randomUUID();
        assertEquals(ExtendedImportJob.punishmentKey(PunishmentType.BAN, target, null),
                ExtendedImportJob.punishmentKey(PunishmentType.TEMPBAN, target, null));
        assertEquals(ExtendedImportJob.punishmentKey(PunishmentType.MUTE, target, null),
                ExtendedImportJob.punishmentKey(PunishmentType.TEMPMUTE, target, null));
        assertFalse(ExtendedImportJob.punishmentKey(PunishmentType.BAN, target, null)
                .equals(ExtendedImportJob.punishmentKey(PunishmentType.MUTE, target, null)));
        assertEquals(ExtendedImportJob.punishmentKey(PunishmentType.IPBAN, null, "10.0.0.1"),
                ExtendedImportJob.punishmentKey(PunishmentType.IPBAN, UUID.randomUUID(), "10.0.0.1"));
    }

    @Test
    void vanillaBanListsParseBansTempBansAndIpBans() {
        String players = """
                [
                  {"uuid":"aaaaaaaa-0000-0000-0000-000000000001","name":"Alex","created":"2024-01-02 03:04:05 +0000",
                   "source":"Server","expires":"forever","reason":"Banned by an operator."},
                  {"uuid":"aaaaaaaa-0000-0000-0000-000000000002","name":"Bob","created":"2024-01-02 03:04:05 +0100",
                   "source":"Notch","expires":"2099-01-01 00:00:00 +0000","reason":"cheating"},
                  {"uuid":"not-a-uuid","name":"Broken"},
                  {"uuid":"aaaaaaaa-0000-0000-0000-000000000003","expires":"someday"},
                  42
                ]""";
        List<VanillaBanParser.Ban> bans = VanillaBanParser.parsePlayers(players);
        assertEquals(5, bans.size());
        VanillaBanParser.Ban alex = bans.get(0);
        assertTrue(alex.ok());
        assertEquals(ALEX, alex.uuid());
        assertNull(alex.expires());
        assertEquals(Instant.parse("2024-01-02T03:04:05Z"), alex.created());
        VanillaBanParser.Ban bob = bans.get(1);
        assertEquals(Instant.parse("2099-01-01T00:00:00Z"), bob.expires());
        assertEquals(Instant.parse("2024-01-02T02:04:05Z"), bob.created());
        assertEquals("Notch", bob.source());
        assertNull(VanillaBanParser.parsePlayers("[{\"uuid\":\"aaaaaaaa-0000-0000-0000-000000000009\"}]").getFirst().created());
        assertFalse(bans.get(2).ok());
        assertFalse(bans.get(3).ok());
        assertFalse(bans.get(4).ok());

        List<VanillaBanParser.Ban> ips = VanillaBanParser.parseIps("""
                [{"ip":"10.0.0.1","created":"2024-01-02 03:04:05 +0000","source":"Server","expires":"forever","reason":"x"},
                 {"created":"2024-01-02 03:04:05 +0000"}]""");
        assertEquals("10.0.0.1", ips.get(0).ip());
        assertNull(ips.get(0).uuid());
        assertFalse(ips.get(1).ok());

        assertTrue(VanillaBanParser.parsePlayers("").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> VanillaBanParser.parsePlayers("{}"));
    }

    // ---- warps ----

    @Test
    void cmiWarpKeepsOwnerCreationTimePermissionAndIcon() throws Exception {
        ConfigurationNode root = YamlConfigurationLoader.builder().buildAndLoadString("""
                market:
                  Location: world;10.5;65.0;-3.5;90.0;12.0
                  Creator: 631e3896-da2a-4077-974b-d047859d76bc
                  CreationTime: 1700000000000
                  Category: town
                  ReqPerm: true
                  HideFromList: true
                  Item: AQID
                """);
        CmiWarpFileParser.ParsedWarp warp = CmiWarpFileParser.parse(root).getFirst();
        assertTrue(warp.ok());
        assertEquals("town", warp.category());
        assertEquals(90f, warp.location().yaw());
        assertEquals(12f, warp.location().pitch());
        assertEquals(UUID.fromString("631e3896-da2a-4077-974b-d047859d76bc"), warp.owner());
        assertEquals(1700000000000L, warp.createdAt());
        assertEquals(Boolean.TRUE, warp.permissionRequired());
        assertEquals(3, warp.icon().length);
        assertTrue(warp.notes().stream().anyMatch(n -> n.contains("hidden")));
    }

    // ---- sources ----

    @Test
    void sourcesListWhatEachFolderCanImport(@TempDir Path server) throws Exception {
        Path cmi = Files.createDirectories(server.resolve("plugins/CMI"));
        Files.createDirectories(cmi.resolve("Saves"));
        Files.writeString(cmi.resolve("Saves/Warps.yml"), "a: {}");
        Files.writeString(cmi.resolve("Saves/Jails.yml"), "a: {}");
        Files.createDirectories(cmi.resolve("Kits"));
        Files.writeString(cmi.resolve("Kits/Kits.yml"), "a: {}");
        Path ess = Files.createDirectories(server.resolve("plugins/Essentials"));
        Files.createDirectories(ess.resolve("userdata"));
        Files.writeString(ess.resolve("userdata/a.yml"), "money: 1");
        Files.writeString(ess.resolve("kits.yml"), "kits: {}");
        Path pvx = Files.createDirectories(server.resolve("plugins/PlayerVaults/newvaults"));
        Files.writeString(pvx.resolve("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.yml"), "vault1: ''");
        Files.writeString(server.resolve("banned-players.json"), "[]");

        List<ImportSources.Source> sources = ImportSources.scan(cmi, "x", ess, "y", null, pvx, "z", server);
        assertEquals(4, sources.size());
        assertTrue(sources.get(0).available());
        assertTrue(sources.get(0).detail().contains("warps"));
        assertTrue(sources.get(0).detail().contains("jails"));
        assertTrue(sources.get(0).detail().contains("1 kit file(s)"));
        assertTrue(sources.get(1).detail().contains("1 userdata file(s)"));
        assertTrue(sources.get(1).detail().contains("kits.yml"));
        assertTrue(sources.get(2).available());
        assertTrue(sources.get(2).detail().contains("1 vault file(s)"));
        assertTrue(sources.get(2).detail().contains("refused"));
        assertTrue(sources.get(3).available());
        assertTrue(sources.get(3).detail().contains("banned-players.json"));

        List<ImportSources.Source> none = ImportSources.scan(null, "/p/CMI", null, "/p/Essentials", null, null,
                "/p/PlayerVaults/newvaults", server.resolve("x"));
        assertFalse(none.get(0).available());
        assertTrue(none.get(0).detail().contains("/p/CMI"));
        assertFalse(none.get(3).available());
        assertFalse(none.get(2).available());
        assertTrue(none.get(2).detail().contains("/p/PlayerVaults/newvaults"));
    }
}
