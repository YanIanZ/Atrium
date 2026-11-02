package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

class EssentialsUserParserTest {
    private static final String FILE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.yml";

    private static ConfigurationNode fixture(String name) throws Exception {
        Path path = Path.of(EssentialsUserParserTest.class.getResource("/fixtures/" + name).toURI());
        return YamlConfigurationLoader.builder().path(path).build().load();
    }

    @Test
    void parsesMoneyNicknameAndTimestamps() throws Exception {
        EssentialsUserParser.UserFile user = EssentialsUserParser.parse(FILE, fixture("essentials-user.yml"));
        assertEquals(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), user.uuid());
        assertEquals("Steve", user.label());
        assertEquals(new BigDecimal("1234.5"), user.money());
        assertNull(user.moneyProblem());
        assertEquals("<yellow>[VIP] <green>Steve", LegacyText.toMiniMessage(user.nickname()));
        assertEquals("[VIP] Steve", LegacyText.plain(LegacyText.toMiniMessage(user.nickname())));
        assertTrue(user.hasTimestamps());
        assertEquals(1600000000000L, user.login());
        assertEquals(1600000100000L, user.logout());
        assertEquals(1600000050000L, user.lastLogin());
        assertNotNull(user.lastLocation());
        assertNotNull(user.logoutLocation());
        assertEquals(1, user.ignored());
        assertEquals(1, user.mail());
        assertTrue(user.banned());
        assertTrue(user.muted());
        assertFalse(user.jailed());
        assertEquals("griefing", user.stateDetail());
    }

    @Test
    void parsesHomesWithWorldUuidAndWorldName() throws Exception {
        EssentialsUserParser.UserFile user = EssentialsUserParser.parse(FILE, fixture("essentials-user.yml"));
        List<EssentialsUserParser.RawHome> homes = user.homes();
        assertEquals(3, homes.size());
        EssentialsUserParser.RawHome home = homes.get(0);
        assertTrue(home.ok());
        assertEquals("home", home.name());
        assertEquals("world", home.location().worldToken());
        assertNull(home.location().worldName());
        assertEquals(10.5, home.location().x());
        assertEquals(64.0, home.location().y());
        assertEquals(-3.25, home.location().z());
        assertEquals(90.0f, home.location().yaw());
        assertEquals(12.5f, home.location().pitch());

        EssentialsUserParser.RawHome nether = homes.get(1);
        assertTrue(nether.ok());
        assertEquals("5f9d6a1e-1111-2222-3333-444455556666", nether.location().worldToken());
        assertEquals("world_nether", nether.location().worldName());

        assertFalse(homes.get(2).ok(), "a home with a malformed coordinate must be reported, not dropped");
        assertNotNull(homes.get(2).problem());
    }

    @Test
    void legacyNameFileHasNoUuidAndIsResolvedByTheCaller() throws Exception {
        EssentialsUserParser.UserFile user = EssentialsUserParser.parse("Steve.yml", fixture("essentials-user.yml"));
        assertNull(user.uuid());
        assertEquals("Steve", user.id());
        assertEquals("Steve", user.label());
    }

    @Test
    void moneyIsAnExactBigDecimal() {
        assertEquals(new BigDecimal("1234.5678"), EssentialsMoney.parse("1234.5678").orElseThrow());
        assertEquals(new BigDecimal("1234.50"), EssentialsMoney.parse("$1,234.50").orElseThrow());
        assertEquals(new BigDecimal("0"), EssentialsMoney.parse("0").orElseThrow());
        assertTrue(EssentialsMoney.parse("-1").isEmpty());
        assertTrue(EssentialsMoney.parse("NaN").isEmpty());
        assertTrue(EssentialsMoney.parse("Infinity").isEmpty());
        assertTrue(EssentialsMoney.parse("1e999999").isEmpty());
        assertTrue(EssentialsMoney.parse("").isEmpty());
    }

    @Test
    void missingOptionalDataIsEmptyNotBroken() throws Exception {
        Path path = Path.of(EssentialsUserParserTest.class.getResource("/fixtures/essentials-warp.yml").toURI());
        ConfigurationNode node = YamlConfigurationLoader.builder().path(path).build().load();
        EssentialsUserParser.UserFile user = EssentialsUserParser.parse("Steve.yml", node);
        assertNull(user.money());
        assertNull(user.nickname());
        assertTrue(user.homes().isEmpty());
        assertNull(user.lastLocation());
        assertFalse(user.hasTimestamps());
        assertFalse(user.banned());
        assertFalse(user.muted());
        assertEquals(0, user.ignored());
    }
}
