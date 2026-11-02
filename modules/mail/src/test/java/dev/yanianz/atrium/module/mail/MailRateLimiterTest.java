package dev.yanianz.atrium.module.mail;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MailRateLimiterTest {
    @Test void allowsThenBlocksUntilCooldownPasses() {
        MailRateLimiter limiter = new MailRateLimiter();
        UUID sender = UUID.randomUUID();

        assertTrue(limiter.check(sender, 1_000, 5_000).allowed());
        MailRateLimiter.Decision denied = limiter.check(sender, 3_000, 5_000);
        assertFalse(denied.allowed());
        assertEquals(3_000, denied.remainingMillis());

        assertTrue(limiter.check(sender, 6_000, 5_000).allowed());
    }

    @Test void zeroCooldownAndClockSkewNeverBlock() {
        MailRateLimiter limiter = new MailRateLimiter();
        UUID sender = UUID.randomUUID();
        assertTrue(limiter.check(sender, 50, 0).allowed());
        assertTrue(limiter.check(sender, 100, 1_000).allowed());
        assertTrue(limiter.check(sender, 50, 1_000).allowed());
    }

    @Test void keepsSendersIndependentAndForgetsThem() {
        MailRateLimiter limiter = new MailRateLimiter();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertTrue(limiter.check(first, 1_000, 1_000).allowed());
        assertTrue(limiter.check(second, 1_000, 1_000).allowed());
        assertFalse(limiter.check(first, 1_500, 1_000).allowed());
        limiter.forget(first);
        assertTrue(limiter.check(first, 1_500, 1_000).allowed());
    }
}
