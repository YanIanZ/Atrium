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
    }

    @Test
    void imageLinesCarryKindPlayerAndSize() {
        assertEquals(new LineContent.Image("Alex", SkinKind.HEAD, 1), LineContent.parse("#img:head:Alex"));
        assertEquals(new LineContent.Image("Alex", SkinKind.BUST, 2), LineContent.parse("#img:bust:Alex:2"));
        assertEquals(new LineContent.Image("Alex", SkinKind.BODY, 4), LineContent.parse("#img:body:Alex:4"));
        assertFalse(LineContent.parse("#img:body:Alex").viewerDependent());
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
        assertThrows(IllegalArgumentException.class, () -> LineContent.parse("#img:bust"));
        assertThrows(IllegalArgumentException.class, () -> LineContent.parse("#img:body:Alex:0"));
        assertThrows(IllegalArgumentException.class, () -> LineContent.parse("#img:body:Alex:5"));
        assertThrows(IllegalArgumentException.class, () -> LineContent.parse("#img:body:Alex:big"));
        assertThrows(IllegalArgumentException.class, () -> LineContent.parse("#img:legs:Alex"));
        assertThrows(IllegalArgumentException.class, () -> LineContent.parse("#cmd:abc"));
        assertThrows(IllegalArgumentException.class, () -> LineContent.parse("#cmd:-4"));
    }

    @Test
    void plainTextAndEscapesStillWork() {
        assertTrue(LineContent.parse("Hello <gray>world").isText());
        assertEquals(new LineContent.Text("#item:not-an-item"), LineContent.parse("\\#item:not-an-item"));
    }
}
