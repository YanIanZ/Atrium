package dev.yanianz.atrium.module.webstore;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandTemplateTest {
    private static final String UUID = "0f8fad5b-d9cb-469f-a165-70867728950e";

    private static Map<String, String> values(String buyer) {
        return CommandTemplate.values(UUID.toUpperCase(), "Steve_1", buyer, "mvp_plus");
    }

    @Test void substitutesAllPlaceholders() throws Exception {
        assertEquals("lp user " + UUID + " parent add mvp_plus",
                CommandTemplate.apply("lp user {uuid} parent add {package}", values("Alex")));
        assertEquals("say Steve_1 got mvp_plus from Alex",
                CommandTemplate.apply("say {player} got {package} from {buyer}", values("Alex")));
        assertEquals("give Steve_1 diamond 1 Steve_1",
                CommandTemplate.apply("/give {player} diamond 1 {player}", values(null)));
    }

    @Test void unknownPlaceholdersAndPlainTextStayUnchanged() throws Exception {
        assertEquals("say {world} {Player} {} hi", CommandTemplate.apply("say {world} {Player} {} hi", values("Alex")));
    }

    @Test void substitutionIsSinglePass() throws Exception {
        // A value that looks like a placeholder must never be expanded again (and is not even allowed here).
        var tricky = CommandTemplate.values(UUID, "Steve", "x", "{uuid}");
        assertThrows(CommandTemplate.UnsafeValueException.class, () -> CommandTemplate.apply("say {package}", tricky));
        var dollar = CommandTemplate.values(UUID, "Steve", "a.b-c_d", "vip");
        assertEquals("say a.b-c_d", CommandTemplate.apply("say {buyer}", dollar));
    }

    @Test void rejectsUnsafeValuesOnlyWhenUsed() throws Exception {
        var evil = CommandTemplate.values(UUID, "Steve", "Alex parent add owner", "vip");
        assertEquals("lp user " + UUID + " parent add vip", CommandTemplate.apply("lp user {uuid} parent add {package}", evil));
        var e = assertThrows(CommandTemplate.UnsafeValueException.class,
                () -> CommandTemplate.apply("say {buyer}", evil));
        assertEquals("buyer", e.placeholder());
        assertThrows(CommandTemplate.UnsafeValueException.class,
                () -> CommandTemplate.apply("say {buyer}", values(null)));
        assertThrows(CommandTemplate.UnsafeValueException.class,
                () -> CommandTemplate.apply("lp user {player} parent add vip",
                        CommandTemplate.values(UUID, "Steve; op Steve", null, "vip")));
        assertThrows(CommandTemplate.UnsafeValueException.class,
                () -> CommandTemplate.apply("lp user {uuid} parent add vip",
                        CommandTemplate.values("not-a-uuid", "Steve", null, "vip")));
        assertThrows(CommandTemplate.UnsafeValueException.class,
                () -> CommandTemplate.apply("lp user {player} parent add vip",
                        CommandTemplate.values(UUID, "ThisNameIsTooLong17", null, "vip")));
    }

    @Test void ignoresBlankAndCommentLines() {
        assertTrue(CommandTemplate.ignored(""));
        assertTrue(CommandTemplate.ignored("   "));
        assertTrue(CommandTemplate.ignored("# note"));
        assertTrue(CommandTemplate.ignored(null));
        assertFalse(CommandTemplate.ignored("lp user {uuid} parent add vip"));
    }
}
