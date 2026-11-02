package dev.yanianz.atrium.module.holograms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LineContentIconsTest {
    @Test
    void aNamedHeadIsParsed() {
        LineContent content = LineContent.parse("#head:Steve");
        assertEquals(new LineContent.Head("Steve"), content);
        assertTrue(content.isItemLike());
        assertFalse(content.viewerDependent());
    }

    @Test
    void anImageHeadWithoutANameBelongsToTheViewer() {
        LineContent content = LineContent.parse("#img:head");
        assertEquals(new LineContent.Head(""), content);
        assertTrue(content.viewerDependent());
        assertEquals(new LineContent.Head("Alex"), LineContent.parse("#img:head:Alex"));
    }

    @Test
    void theHeldItemIsAnIcon() {
        assertEquals(new LineContent.Icon(LineContent.IconKind.HAND, 0), LineContent.parse("#iteminhand"));
        assertEquals(new LineContent.Icon(LineContent.IconKind.HAND, 0), LineContent.parse("%itemInHand%"));
        assertTrue(LineContent.parse("#iteminhand").viewerDependent());
    }

    @Test
    void customModelDataIconsAreParsed() {
        assertEquals(new LineContent.Icon(LineContent.IconKind.CUSTOM_MODEL_DATA, 1042),
                LineContent.parse("#cmd:1042"));
        assertEquals(new LineContent.Icon(LineContent.IconKind.CUSTOM_MODEL_DATA, 7),
                LineContent.parse("%CustomModelData:7%"));
        assertFalse(LineContent.parse("#cmd:7").viewerDependent());
    }

    @Test
    void invalidValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> LineContent.parse("#head:"));
        assertThrows(IllegalArgumentException.class, () -> LineContent.parse("#head:bad name"));
        assertThrows(IllegalArgumentException.class, () -> LineContent.parse("#img:head:bad name"));
        assertThrows(IllegalArgumentException.class, () -> LineContent.parse("#cmd:abc"));
        assertThrows(IllegalArgumentException.class, () -> LineContent.parse("#cmd:-4"));
    }

    @Test
    void plainTextAndEscapesStillWork() {
        assertTrue(LineContent.parse("Hello <gray>world").isText());
        assertEquals(new LineContent.Text("#item:not-an-item"), LineContent.parse("\\#item:not-an-item"));
    }
}
