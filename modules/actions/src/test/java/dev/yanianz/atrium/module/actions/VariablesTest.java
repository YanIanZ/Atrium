package dev.yanianz.atrium.module.actions;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class VariablesTest {
    private static String strict(String template, Map<String, String> vars) throws Variables.UnsafeValueException {
        return Variables.substituteStrict(template, vars);
    }

    private static void rejected(String key, String value) {
        assertThrows(Variables.UnsafeValueException.class, () -> strict("cmd {" + key + "}", Map.of(key, value)),
                () -> "should reject " + key + "=" + value);
    }

    @Test void substitutesSafeValues() throws Exception {
        assertEquals("give Steve_1 diamond 5", strict("give {player} diamond {arg1}", Map.of("player", "Steve_1", "arg1", "5")));
        assertEquals("pay a -1.5 minecraft:stone ok!", strict("pay a {n} {item} {t}", Map.of("n", "-1.5", "item", "minecraft:stone", "t", "ok!")));
        assertEquals("say hello world", strict("say {args}", Map.of("args", "hello world")));
        assertEquals("say ", strict("say {args}", Map.of("args", "")));
    }

    @Test void rejectsInjectionAttempts() {
        rejected("arg1", "@a");
        rejected("arg1", "@e[type=player]");
        rejected("arg1", "x; op me");
        rejected("arg1", "{\"text\":\"x\"}");
        rejected("arg1", "%player_name%");
        rejected("arg1", "a\nb");
        rejected("arg1", "§4red");
        rejected("arg1", "&4red");
        rejected("arg1", "\"quoted\"");
        rejected("arg1", "a/b");
        rejected("arg1", "~ ~ ~");
        rejected("arg1", " leading");
        rejected("arg1", "double  space");
        rejected("arg1", "x".repeat(300));
        rejected("arg1", "<red>x");
        rejected("player", "not a name");
        rejected("player", "WayTooLongPlayerName123");
        rejected("player", "Steve.");
    }

    @Test void unknownReferenceInCommandIsRejected() {
        var e = assertThrows(Variables.UnsafeValueException.class, () -> strict("kick {target}", Map.of()));
        assertEquals("target", e.variable);
    }

    @Test void substitutionIsSinglePass() throws Exception {
        // A value that looks like a reference is inserted literally... and is then rejected as unsafe for commands.
        rejected("arg1", "{arg2}");
        // In messages it stays literal text.
        Map<String, String> vars = Map.of("arg1", "{arg2}", "arg2", "SECRET");
        String plain = PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage()
                .deserialize(Variables.toMiniMessage("v={arg1}", vars), Variables.resolver(vars)));
        assertEquals("v={arg2}", plain);
    }

    @Test void messageValuesAreNeverParsed() {
        Map<String, String> vars = Map.of("args", "<red><click:run_command:/op me>pwn</click>");
        var component = MiniMessage.miniMessage().deserialize(Variables.toMiniMessage("<green>Report: {args} {unknown}", vars),
                Variables.resolver(vars));
        assertEquals("Report: <red><click:run_command:/op me>pwn</click> {unknown}",
                PlainTextComponentSerializer.plainText().serialize(component));
        assertFalse(component.toString().contains("ClickEvent"));
    }

    @Test void mergeKeepsBuiltInsAndDropsBadKeys() {
        Map<String, String> merged = Variables.merge(Map.of("player", "Steve"),
                Map.of("player", "Admin", "Arg1", "x", "bad key", "y", "ok_2", "z"));
        assertEquals("Steve", merged.get("player"));
        assertEquals("x", merged.get("arg1"));
        assertEquals("z", merged.get("ok_2"));
        assertFalse(merged.containsKey("bad key"));
    }

    @Test void safeCharsetRules() {
        assertTrue(Variables.isSafe("player", "Notch"));
        assertTrue(Variables.isSafe("uuid", "123e4567-e89b-12d3-a456-426614174000"));
        assertTrue(Variables.isSafe("x", "-120"));
        assertFalse(Variables.isSafe("x", "1,2;3"));
        assertTrue(Variables.hasReference("a {b} c"));
        assertFalse(Variables.hasReference("a {B} c"));
    }
}
