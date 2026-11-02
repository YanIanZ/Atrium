package dev.yanianz.atrium.module.localeeditor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.yanianz.atrium.module.localeeditor.LangDocument.InvalidDocumentException;
import org.junit.jupiter.api.Test;

class LocaleReportTest {
    @Test
    void messageKeysAlreadyCarryTheModulePrefix() {
        // Regression: report() used to prepend "localeeditor." again, producing
        // "localeeditor.localeeditor.section-key" and a raw key in the player's chat.
        assertEquals("localeeditor.section-key", LocaleReport.messageKey(InvalidDocumentException.Reason.SECTION));
        assertEquals("localeeditor.verify-failed", LocaleReport.messageKey(InvalidDocumentException.Reason.VERIFY));
        assertEquals("localeeditor.parse-failed", LocaleReport.messageKey(InvalidDocumentException.Reason.PARSE));
    }
}
