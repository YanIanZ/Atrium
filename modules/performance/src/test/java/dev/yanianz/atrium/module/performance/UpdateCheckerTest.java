package dev.yanianz.atrium.module.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UpdateCheckerTest {
    private static final String BODY = """
            {
              "tag_name": "26.2.1",
              "html_url": "https://github.com/YanIanZ/Atrium/releases/tag/26.2.1",
              "draft": false,
              "prerelease": false,
              "name": "Atrium 26.2.1"
            }
            """;

    @Test
    void readsTheTagAndTheLink() {
        UpdateChecker.Status status = UpdateChecker.parse(BODY, "26.1.2");
        assertTrue(status.checked());
        assertTrue(status.newer());
        assertEquals("26.2.1", status.latest());
        assertEquals("https://github.com/YanIanZ/Atrium/releases/tag/26.2.1", status.url());
        assertEquals("", status.error());
    }

    @Test
    void theSameOrAnOlderReleaseIsNotNewer() {
        assertFalse(UpdateChecker.parse(BODY, "26.2.1").newer());
        assertFalse(UpdateChecker.parse(BODY, "26.3").newer());
    }

    @Test
    void aDraftIsIgnored() {
        String draft = BODY.replace("\"draft\": false", "\"draft\": true");
        UpdateChecker.Status status = UpdateChecker.parse(draft, "26.1.2");
        assertTrue(status.checked());
        assertFalse(status.newer());
    }

    @Test
    void anUnexpectedAnswerIsReportedAsAnError() {
        UpdateChecker.Status status = UpdateChecker.parse("{\"message\": \"Not Found\"}", "1.0.0");
        assertTrue(status.checked());
        assertFalse(status.newer());
        assertFalse(status.error().isEmpty());
    }

    @Test
    void anIncomparableTagIsReported() {
        String body = BODY.replace("26.2.1", "latest");
        assertFalse(UpdateChecker.parse(body, "1.0.0").error().isEmpty());
    }
}
