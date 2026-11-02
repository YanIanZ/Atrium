package dev.yanianz.atrium.module.holograms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Front-face pixel mapping and MiniMessage colour output, using a decoded synthetic PNG. */
class SkinImageTest {
    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFF0000FF;
    private static final int YELLOW = 0xFFFFFF00;
    private static final int MAGENTA = 0xFFFF00FF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;

    private static BufferedImage png(BufferedImage source) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(source, "png", out);
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
            assertEquals(source.getWidth(), decoded.getWidth());
            return decoded;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void fill(BufferedImage image, int x, int y, int w, int h, int argb) {
        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                image.setRGB(x + dx, y + dy, argb);
            }
        }
    }

    @Test
    void theHeadUsesTheBaseFaceAndTheHatOverlay() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(image, 8, 8, 8, 8, RED);
        image.setRGB(40 + 2, 8 + 1, GREEN);
        BufferedImage decoded = png(image);

        assertEquals(RED, SkinImage.sourceArgb(decoded, SkinKind.HEAD, 0, 0));
        assertEquals(GREEN, SkinImage.sourceArgb(decoded, SkinKind.HEAD, 2, 1));
        List<String> rows = SkinImage.rows(decoded, SkinKind.HEAD, 1);
        assertEquals(8, rows.size());
        assertTrue(rows.getFirst().contains("<color:#FF0000>"));
        assertTrue(rows.get(1).contains("<color:#00FF00>"));
    }

    @Test
    void bustMapsTorsoAndBothArms() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(image, 20, 20, 8, 12, BLUE);
        fill(image, 44, 20, 4, 12, YELLOW);
        fill(image, 36, 52, 4, 12, MAGENTA);
        BufferedImage decoded = png(image);

        assertEquals(BLUE, SkinImage.sourceArgb(decoded, SkinKind.BUST, 4, 8));
        assertEquals(YELLOW, SkinImage.sourceArgb(decoded, SkinKind.BUST, 0, 8));
        assertEquals(MAGENTA, SkinImage.sourceArgb(decoded, SkinKind.BUST, 12, 8));
        assertEquals(20, SkinImage.rows(decoded, SkinKind.BUST, 1).size());
        assertTrue(SkinImage.rows(decoded, SkinKind.BUST, 1).get(8).contains("<color:#0000FF>"));
    }

    @Test
    void bodyAddsBothLegsBelowTheTorso() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(image, 4, 20, 4, 12, WHITE);
        fill(image, 20, 52, 4, 12, BLACK);
        BufferedImage decoded = png(image);

        assertEquals(WHITE, SkinImage.sourceArgb(decoded, SkinKind.BODY, 4, 20));
        assertEquals(BLACK, SkinImage.sourceArgb(decoded, SkinKind.BODY, 8, 20));
        assertEquals(0, SkinImage.sourceArgb(decoded, SkinKind.BODY, 0, 20));
        assertEquals(32, SkinImage.rows(decoded, SkinKind.BODY, 1).size());
    }

    @Test
    void transparentPixelsBecomeSpacesAndSizeRepeatsEveryPixel() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(8, 8, RED);
        BufferedImage decoded = png(image);
        String row = SkinImage.rows(decoded, SkinKind.HEAD, 1).getFirst();
        assertTrue(row.startsWith("<color:#FF0000>█</color>"));
        assertTrue(row.endsWith("       "));

        List<String> doubled = SkinImage.rows(decoded, SkinKind.HEAD, 2);
        assertEquals(16, doubled.size());
        assertEquals(2, doubled.getFirst().chars().filter(c -> c == '█').count());
    }

    @Test
    void theDefaultSkinIsARenderable64x64Skin() {
        BufferedImage image = SkinImage.defaultSkin();
        assertEquals(64, image.getWidth());
        assertEquals(64, image.getHeight());
        assertEquals(8, SkinImage.rows(image, SkinKind.HEAD, 1).size());
        assertTrue(SkinImage.rows(image, SkinKind.BODY, 1).stream().anyMatch(row -> row.contains("█")));
    }
}
