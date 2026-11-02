package dev.yanianz.atrium.module.actions;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.core.util.Durations;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** Pure cron maths and schedule validation. */
class CronScheduleTest {
    private static final ZoneId ZONE = ZoneId.of("UTC");

    private static ZonedDateTime at(String iso) {
        return ZonedDateTime.parse(iso + "Z").withZoneSameInstant(ZONE);
    }

    @Test void nextMinuteForEveryMinute() {
        CronSchedule cron = CronSchedule.parse("* * * * *");
        assertEquals(at("2026-03-01T12:01"), cron.next(at("2026-03-01T12:00")).orElseThrow());
        assertEquals(at("2026-03-01T12:01"), cron.next(at("2026-03-01T12:00:30")).orElseThrow());
    }

    @Test void dailyTime() {
        CronSchedule cron = CronSchedule.parse("0 8 * * *");
        assertEquals(at("2026-03-02T08:00"), cron.next(at("2026-03-01T12:00")).orElseThrow());
        assertEquals(at("2026-03-01T08:00"), cron.next(at("2026-03-01T07:59")).orElseThrow());
        // Exactly on the tick: the next match is the following day.
        assertEquals(at("2026-03-02T08:00"), cron.next(at("2026-03-01T08:00")).orElseThrow());
    }

    @Test void stepsAndRanges() {
        CronSchedule cron = CronSchedule.parse("*/15 * * * *");
        assertEquals(at("2026-03-01T12:15"), cron.next(at("2026-03-01T12:07")).orElseThrow());
        CronSchedule workHours = CronSchedule.parse("30 9-17 * * MON-FRI");
        assertEquals(at("2026-03-02T09:30"), workHours.next(at("2026-03-01T20:00")).orElseThrow());
        // Friday 18:00 -> Monday 09:30 (2026-03-06 is a Friday).
        assertEquals(at("2026-03-09T09:30"), workHours.next(at("2026-03-06T18:00")).orElseThrow());
    }

    @Test void namedMonthsAndWeekdays() {
        CronSchedule cron = CronSchedule.parse("0 0 1 JAN *");
        assertEquals(at("2027-01-01T00:00"), cron.next(at("2026-03-01T00:00")).orElseThrow());
        CronSchedule sunday = CronSchedule.parse("0 12 * * 7");
        // 7 and 0 are both Sunday; 2026-03-01 is a Sunday.
        assertEquals(at("2026-03-01T12:00"), sunday.next(at("2026-03-01T11:00")).orElseThrow());
    }

    @Test void dayOfMonthAndDayOfWeekEitherMatches() {
        CronSchedule cron = CronSchedule.parse("0 0 1 * MON");
        // 2026-03-01 is a Sunday but the first of the month, so it matches.
        assertEquals(at("2026-03-01T00:00"), cron.next(at("2026-02-28T12:00")).orElseThrow());
    }

    @Test void invalidExpressionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse(""));
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("* * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("60 * * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("*/0 * * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("* * 0 * *"));
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("* * * * FUNDAY"));
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("5-1 * * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("* * * * *,"));
    }

    @Test void expressionMatchesTheMinuteItself() {
        CronSchedule cron = CronSchedule.parse("30 14 * * *");
        assertTrue(cron.matches(at("2026-03-01T14:30")));
        assertFalse(cron.matches(at("2026-03-01T14:31")));
        assertEquals("30 14 * * *", cron.expression());
    }

    @Test void scheduleFileValidation(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("schedules.yml");
        java.nio.file.Files.writeString(file, """
                schedules:
                  morning:
                    cron: "0 8 * * *"
                    min-players: 1
                    max-players: 20
                    actions: ["msg: hi"]
                  frequent:
                    every: 1h
                    commands: ["say hello"]
                  both:
                    cron: "* * * * *"
                    every: 10m
                    actions: ["msg: no"]
                  none:
                    actions: ["msg: no"]
                  broken:
                    cron: "nope"
                    actions: ["msg: no"]
                  empty:
                    every: 1h
                    actions: []
                  Bad Id:
                    every: 1h
                    actions: ["msg: no"]
                  paused:
                    enabled: false
                    every: 1h
                    actions: ["msg: no"]
                """);
        var root = YamlConfigurationLoader.builder().path(file).build().load();
        List<SchedulesFile.Job> jobs = SchedulesFile.parse(root, LoggerFactory.getLogger(getClass()));
        // Invalid entries are skipped, the disabled one is kept (the engine decides whether to run it).
        assertEquals(3, jobs.size());
        SchedulesFile.Job disabled = jobs.stream().filter(job -> job.id().equals("paused")).findFirst().orElseThrow();
        assertFalse(disabled.enabled());
        SchedulesFile.Job morning = jobs.stream().filter(job -> job.id().equals("morning")).findFirst().orElseThrow();
        assertEquals("0 8 * * *", morning.cron().expression());
        assertFalse(morning.intervalBased());
        assertTrue(morning.allowsPlayers(1));
        assertFalse(morning.allowsPlayers(0));
        assertFalse(morning.allowsPlayers(21));

        SchedulesFile.Job frequent = jobs.stream().filter(job -> job.id().equals("frequent"))
                .findFirst().orElseThrow();
        assertEquals(List.of("console: say hello"), frequent.actions());
        assertTrue(frequent.intervalBased());
        assertEquals(Durations.parse("1h").orElseThrow(), frequent.every());
    }

    @Test void missingFileIsNotAnError(@TempDir Path directory) throws Exception {
        assertTrue(SchedulesFile.load(directory.resolve("nope.yml"), LoggerFactory.getLogger(getClass())).isEmpty());
        Path file = directory.resolve("schedules.yml");
        SchedulesFile.writeExampleIfMissing(file);
        assertTrue(java.nio.file.Files.exists(file));
        assertTrue(SchedulesFile.load(file, LoggerFactory.getLogger(getClass())).isEmpty());
        // The example is written once and never overwritten.
        java.nio.file.Files.writeString(file, "schedules: {}");
        SchedulesFile.writeExampleIfMissing(file);
        assertEquals("schedules: {}", java.nio.file.Files.readString(file));
    }

    @Test void nextIsAlwaysStrictlyInTheFuture() {
        CronSchedule cron = CronSchedule.parse("* * * * *");
        Optional<ZonedDateTime> next = cron.next(at("2026-12-31T23:59"));
        assertEquals(at("2027-01-01T00:00"), next.orElseThrow());
    }
}
