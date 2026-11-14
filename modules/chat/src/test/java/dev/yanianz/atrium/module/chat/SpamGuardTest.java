package dev.yanianz.atrium.module.chat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpamGuardTest {
    @Test
    void similarityCanHaveItsOwnWindowWhenExactDuplicateCheckIsDisabled() {
        ChatConfig.AntiSpam config = new ChatConfig.AntiSpam();
        config.minimumIntervalMillis = 0;
        config.duplicateWindowSeconds = 0;
        config.similarityEnabled = true;
        config.similarityWindowSeconds = 4;
        config.similarityPercentage = 80;
        config.similarityMinAmount = 1;
        SpamGuard guard = new SpamGuard();
        UUID id = UUID.randomUUID();
        SpamGuard.Settings settings = SpamGuard.Settings.of(config);
        assertEquals(SpamGuard.Result.ALLOWED, guard.check(id, "hello world", 0, settings));
        assertEquals(SpamGuard.Result.SIMILAR, guard.check(id, "hello worlds", 3999, settings));
        assertEquals(SpamGuard.Result.ALLOWED, guard.check(id, "hello worlds", 4000, settings));
    }

    @Test
    void negativeSimilarityWindowKeepsTheExistingDuplicateWindowPolicy() {
        ChatConfig.AntiSpam config = new ChatConfig.AntiSpam();
        config.duplicateWindowSeconds = 7;
        assertEquals(7000, SpamGuard.Settings.of(config).similarityWindowMillis());
        config.similarityWindowSeconds = 0;
        assertEquals(0, SpamGuard.Settings.of(config).similarityWindowMillis());
    }

    @Test
    void rejectsMessagesThatAreTooCloseTogether() {
        SpamGuard guard = new SpamGuard();
        UUID player = UUID.randomUUID();
        SpamGuard.Settings settings = new SpamGuard.Settings(true, 1000, 5000);
        assertEquals(SpamGuard.Result.ALLOWED, guard.check(player, "hello", 0, settings));
        assertEquals(SpamGuard.Result.TOO_FAST, guard.check(player, "another", 500, settings));
        assertEquals(SpamGuard.Result.ALLOWED, guard.check(player, "another", 1000, settings));
    }

    @Test
    void rejectsDuplicatesInsideTheConfiguredWindow() {
        SpamGuard guard = new SpamGuard();
        UUID player = UUID.randomUUID();
        SpamGuard.Settings settings = new SpamGuard.Settings(true, 0, 5000);
        assertEquals(SpamGuard.Result.ALLOWED, guard.check(player, "Spam!", 0, settings));
        assertEquals(SpamGuard.Result.DUPLICATE, guard.check(player, "spam", 1000, settings));
        assertEquals(SpamGuard.Result.DUPLICATE, guard.check(player, "s.p.a.m", 4999, settings));
        assertEquals(SpamGuard.Result.ALLOWED, guard.check(player, "spam", 5000, settings));
    }

    @Test
    void differentMessagesAndDisabledSettingsPass() {
        SpamGuard guard = new SpamGuard();
        UUID player = UUID.randomUUID();
        assertEquals(SpamGuard.Result.ALLOWED,
                guard.check(player, "one", 0, new SpamGuard.Settings(true, 0, 5000)));
        assertEquals(SpamGuard.Result.ALLOWED,
                guard.check(player, "two", 1, new SpamGuard.Settings(true, 0, 5000)));
        assertEquals(SpamGuard.Result.ALLOWED,
                guard.check(player, "two", 2, new SpamGuard.Settings(false, 100000, 100000)));
    }

    @Test
    void forgetClearsThePlayerState() {
        SpamGuard guard = new SpamGuard();
        UUID player = UUID.randomUUID();
        SpamGuard.Settings settings = new SpamGuard.Settings(true, 1000, 5000);
        assertEquals(SpamGuard.Result.ALLOWED, guard.check(player, "hello", 0, settings));
        guard.forget(player);
        assertEquals(SpamGuard.Result.ALLOWED, guard.check(player, "hello", 1, settings));
    }

    @Test
    void similarityRejectsNearDuplicates() {
        SpamGuard guard = new SpamGuard();
        UUID player = UUID.randomUUID();
        SpamGuard.Settings settings = new SpamGuard.Settings(true, 0, 0, true, 80, 2, 5000);

        assertEquals(SpamGuard.Result.ALLOWED, guard.check(player, "hello everyone", 0, settings));
        assertEquals(SpamGuard.Result.ALLOWED, guard.check(player, "hello every one", 100, settings));
        assertEquals(SpamGuard.Result.SIMILAR, guard.check(player, "hello everyonne", 200, settings));
    }

    @Test
    void similarityMetricIsNormalised() {
        assertEquals(1.0, SpamGuard.similarity("", ""), 0.0001);
        assertEquals(1.0, SpamGuard.similarity("Hello", "hello"), 0.0001);
        assertEquals(0.5, SpamGuard.similarity("abcd", "abxx"), 0.0001);
    }
}
