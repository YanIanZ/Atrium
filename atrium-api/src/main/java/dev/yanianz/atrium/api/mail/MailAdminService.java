package dev.yanianz.atrium.api.mail;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/** Mail import for migration tools, published by the {@code mail} module. */
public interface MailAdminService {
    /**
     * One message as stored by another plugin.
     *
     * @param sender  sending account, or {@code null} for the console or an unknown sender
     * @param created epoch millis
     */
    record ImportedMail(@Nullable UUID sender, String senderName, String body, long created, boolean read) {
        public ImportedMail {
            Objects.requireNonNull(senderName, "senderName");
            Objects.requireNonNull(body, "body");
        }
    }

    /**
     * Stores messages in the recipient's mailbox. A message identical to one already stored (same sender
     * name, text and time) is skipped, so a repeated import never duplicates mail. Imported mail never
     * expires and ignores the mailbox size limit.
     *
     * @return the number of messages actually stored
     */
    CompletableFuture<Integer> importMail(UUID recipient, List<ImportedMail> mail);
}
