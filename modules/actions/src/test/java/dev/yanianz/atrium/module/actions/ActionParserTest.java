package dev.yanianz.atrium.module.actions;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActionParserTest {
    private static ActionLine parse(String line) throws ActionSyntaxException {
        return ActionParser.parse(1, line).orElseThrow();
    }

    private static String error(String line) {
        return assertThrows(ActionSyntaxException.class, () -> ActionParser.parse(1, line)).getMessage();
    }

    @Test void parsesEveryExecutor() throws Exception {
        assertEquals(Executor.CONSOLE, parse("console: /give {player} diamond 1").executor());
        assertEquals("give {player} diamond 1", parse("console: /give {player} diamond 1").argument());
        assertEquals(Executor.PLAYER, parse("player: spawn").executor());
        assertEquals(Executor.MSG, parse("msg: <green>Hello {player}").executor());
        assertEquals(Executor.MSG, parse("message: hi").executor());
        assertEquals(Executor.ACTIONBAR, parse("actionbar: <gold>hey").executor());
        assertEquals(Executor.TITLE, parse("title: <gold>Welcome|<gray>sub").executor());
        assertEquals(Executor.SOUND, parse("sound: entity.player.levelup 1 1.5").executor());
        assertEquals(Executor.DELAY, parse("delay: 2s").executor());
        assertEquals(Executor.TAKE_MONEY, parse("take-money: 10.5").executor());
        assertEquals(Executor.GIVE_MONEY, parse("give-money: 10").executor());
        assertEquals(Executor.TELEPORT, parse("teleport: world 0 64 0 90 10").executor());
        assertEquals(Executor.WARP, parse("warp: spawn").executor());
        assertEquals(Executor.STOP, parse("stop").executor());
        assertEquals(Executor.STOP, parse("stop: <red>bye").executor());
        assertEquals("<red>bye", parse("stop: <red>bye").argument());
        assertEquals(Executor.CONSOLE, parse("CONSOLE: say hi").executor());
    }

    @Test void parsesEveryCondition() throws Exception {
        ActionLine line = parse("[perm:atrium.vip] [!money:10.5] [world:world_nether] [cooldown:daily:1d] [chance:25] msg: x");
        assertEquals(List.of(
                new Condition.Permission("atrium.vip", false),
                new Condition.Money(new BigDecimal("10.5"), true),
                new Condition.World("world_nether", false),
                new Condition.Cooldown("daily", Duration.ofDays(1), false),
                new Condition.Chance(25, false)), line.conditions());
        assertFalse(line.isGuard());
        assertEquals(new Condition.Chance(12.5, true), parse("[!chance:12.5%]").conditions().getFirst());
    }

    @Test void conditionOnlyLineIsGuard() throws Exception {
        ActionLine guard = parse("[perm:x.y]");
        assertTrue(guard.isGuard());
        assertNull(guard.executor());
    }

    @Test void blankAndCommentLinesAreSkipped() throws Exception {
        assertTrue(ActionParser.parse(1, "   ").isEmpty());
        assertTrue(ActionParser.parse(1, "# note").isEmpty());
        assertEquals(1, ActionParser.parseAll(List.of("# c", "", "msg: hi")).size());
    }

    @Test void delaysRoundUpAndAcceptTicks() throws Exception {
        assertEquals(40, parse("delay: 2s").delayTicks());
        assertEquals(40, parse("delay: 40t").delayTicks());
        assertEquals(1, parse("delay: 10ms").delayTicks());
        assertEquals(0, parse("delay: 0").delayTicks());
        assertTrue(error("delay: 2d").contains("24h"));
        assertTrue(error("delay: {x}").contains("literal"));
        assertTrue(error("delay: soon").contains("invalid delay"));
    }

    @Test void opExecutorIsRefused() {
        assertTrue(error("op: give {player} diamond").contains("not supported"));
        assertTrue(error("asop: ban x").contains("not supported"));
    }

    @Test void rejectsBrokenLines() {
        assertTrue(error("hello world").contains("expected 'executor: argument'"));
        assertTrue(error("foo: bar").contains("unknown executor"));
        assertTrue(error("[perm:x msg: hi").contains("unclosed"));
        assertTrue(error("[weather:rain] msg: x").contains("unknown condition"));
        assertTrue(error("[perm:a b] msg: x").contains("invalid permission"));
        assertTrue(error("[money:-5] msg: x").contains("positive"));
        assertTrue(error("[chance:0] msg: x").contains("chance"));
        assertTrue(error("[chance:150] msg: x").contains("chance"));
        assertTrue(error("[cooldown:key] msg: x").contains("cooldown"));
        assertTrue(error("[cooldown:key:0s] msg: x").contains("duration"));
        assertTrue(error("[perm:{arg1}] msg: x").contains("variables"));
        assertTrue(error("console: ").contains("missing command"));
        assertTrue(error("console: /").contains("missing command"));
        assertTrue(error("msg:").contains("missing text"));
        assertTrue(error("take-money: abc").contains("invalid amount"));
        assertTrue(error("take-money: 0").contains("positive"));
        assertTrue(error("give-money: 1.123456").contains("decimals"));
        assertTrue(error("teleport: world 1 2").contains("teleport"));
        assertTrue(error("teleport: world 1 a 3").contains("invalid number"));
        assertTrue(error("teleport: world 1 2 3 0 100").contains("pitch"));
        assertTrue(error("sound: bad:key:here!").contains("sound"));
        assertTrue(error("sound: block.note_block.pling 20").contains("volume"));
        assertTrue(error("warp: bad/name").contains("warp"));
        assertTrue(error("msg: a\nconsole: op me").contains("line breaks"));
    }

    @Test void variablesDeferArgumentValidation() throws Exception {
        assertEquals("{amount}", parse("take-money: {amount}").argument());
        assertEquals("{world} {x} {y} {z}", parse("teleport: {world} {x} {y} {z}").argument());
    }

    @Test void validateReportsEveryBrokenLineWithItsNumber() {
        List<String> errors = ActionParser.validate(List.of("msg: ok", "nope", "[perm:x]", "foo: x"));
        assertEquals(2, errors.size());
        assertTrue(errors.get(0).startsWith("line 2:"));
        assertTrue(errors.get(1).startsWith("line 4:"));
        assertTrue(ActionParser.validate(List.of("console: say hi", "delay: 1s")).isEmpty());
    }

    @Test void runtimeArgumentParsers() throws Exception {
        var sound = ActionParser.sound(1, "minecraft:entity.experience_orb.pickup 0.5 2");
        assertEquals("minecraft:entity.experience_orb.pickup", sound.key().asString());
        assertEquals(0.5f, sound.volume());
        assertEquals(2f, sound.pitch());
        var location = ActionParser.location(1, "world -10.5 64 3");
        assertEquals("world", location.world());
        assertEquals(-10.5, location.x());
        assertEquals("spawn", ActionParser.warp(1, " spawn "));
    }
}
