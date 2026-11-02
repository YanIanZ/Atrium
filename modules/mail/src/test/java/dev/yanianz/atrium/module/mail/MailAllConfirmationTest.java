package dev.yanianz.atrium.module.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.user.PlayerRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MailAllConfirmationTest {
    private static final long NOW = 1_000_000L;
    private static final List<PlayerRef> RECIPIENTS = List.of(
            new PlayerRef(UUID.randomUUID(), "Alice"),
            new PlayerRef(UUID.randomUUID(), "Bob"));

    @Test
    void aConfirmedBlastCarriesTheExactRecipientList() {
        MailAllConfirmation confirmations = new MailAllConfirmation();
        confirmations.arm("p:1", "hello", RECIPIENTS, NOW, 30_000);

        Optional<MailAllConfirmation.Pending> pending = confirmations.take("p:1", NOW + 1);
        assertTrue(pending.isPresent());
        assertEquals("hello", pending.get().message());
        assertEquals(RECIPIENTS, pending.get().recipients());
        assertFalse(confirmations.take("p:1", NOW + 1).isPresent(), "a blast is confirmed only once");
    }

    @Test
    void anExpiredConfirmationCannotSend() {
        MailAllConfirmation confirmations = new MailAllConfirmation();
        confirmations.arm("p:1", "hello", RECIPIENTS, NOW, 1_000);
        assertTrue(confirmations.take("p:1", NOW + 1_000).isEmpty(), "the window edge already expired");
    }

    @Test
    void aSecondArmReplacesTheFirst() {
        MailAllConfirmation confirmations = new MailAllConfirmation();
        confirmations.arm("p:1", "first", RECIPIENTS, NOW, 30_000);
        confirmations.arm("p:1", "second", List.of(RECIPIENTS.getFirst()), NOW, 30_000);
        MailAllConfirmation.Pending pending = confirmations.take("p:1", NOW).orElseThrow();
        assertEquals("second", pending.message());
        assertEquals(1, pending.recipients().size());
    }

    @Test
    void cancelForgetAndClearRemoveThePendingBlast() {
        MailAllConfirmation confirmations = new MailAllConfirmation();
        confirmations.arm("p:1", "hello", RECIPIENTS, NOW, 30_000);
        confirmations.cancel("p:1");
        assertTrue(confirmations.take("p:1", NOW).isEmpty());

        confirmations.arm("p:2", "hello", RECIPIENTS, NOW, 30_000);
        confirmations.forget("p:2");
        assertEquals(0, confirmations.size());

        confirmations.arm("console", "hello", RECIPIENTS, NOW, 30_000);
        confirmations.clear();
        assertEquals(0, confirmations.size());
    }

    @Test
    void theRecipientListCannotBeMutatedFromOutside() {
        MailAllConfirmation confirmations = new MailAllConfirmation();
        java.util.ArrayList<PlayerRef> mutable = new java.util.ArrayList<>(RECIPIENTS);
        confirmations.arm("p:1", "hello", mutable, NOW, 30_000);
        mutable.clear();
        assertEquals(2, confirmations.take("p:1", NOW).orElseThrow().recipients().size());
    }
}
