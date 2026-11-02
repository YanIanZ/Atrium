package dev.yanianz.atrium.module.holograms;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a 64x64 Minecraft skin into the front view of a head, bust or full body, one text row per skin row.
 * Opaque pixels become {@code █} with their own colour; transparent pixels become spaces, so the hat layer
 * keeps its cut-outs. Pure: no server objects, unit tested with a synthetic image.
 */
final class SkinImage {
    static final String PIXEL = "█";
    private static final int OPAQUE = 128;

    private SkinImage() {}

    /** Front faces in the skin texture: base and overlay of the head, torso, arms and legs. */
    private static final int HEAD_X = 8;
    private static final int HEAD_Y = 8;
    private static final int HAT_X = 40;
    private static final int HAT_Y = 8;
    private static final int TORSO_X = 20;
    private static final int TORSO_Y = 20;
    private static final int RIGHT_ARM_X = 44;
    private static final int LEFT_ARM_X = 36;
    private static final int ARM_Y = 20;
    private static final int LEFT_ARM_Y = 52;
    private static final int RIGHT_LEG_X = 4;
    private static final int LEFT_LEG_X = 20;
    private static final int LEG_Y = 20;
    private static final int LEFT_LEG_Y = 52;

    /**
     * Rows of MiniMessage text, top to bottom. {@code size} repeats every skin pixel (1-4).
     *
     * @throws IllegalArgumentException for a null/small image or an out-of-range size
     */
    static List<String> rows(BufferedImage skin, SkinKind kind, int size) {
        if (skin == null || skin.getWidth() < 64 || skin.getHeight() < 64) {
            throw new IllegalArgumentException("a skin must be a 64x64 PNG");
        }
        if (size < 1 || size > 4) {
            throw new IllegalArgumentException("image size must be from 1 to 4");
        }
        int width = kind.width();
        int height = kind.height();
        List<String> rows = new ArrayList<>(height * size);
        for (int y = 0; y < height; y++) {
            String row = row(skin, kind, y, width, size);
            for (int repeat = 0; repeat < size; repeat++) {
                rows.add(row);
            }
        }
        return List.copyOf(rows);
    }

    private static String row(BufferedImage skin, SkinKind kind, int y, int width, int size) {
        int pixels = width * size;
        StringBuilder out = new StringBuilder(pixels * 24);
        int runColour = 0;
        int runLength = 0;
        for (int x = 0; x < pixels; x++) {
            int argb = sourceArgb(skin, kind, x / size, y);
            boolean opaque = alpha(argb) >= OPAQUE;
            int colour = opaque ? argb & 0xFFFFFF : -1;
            if (runLength > 0 && colour != runColour) {
                appendRun(out, runColour, runLength);
                runLength = 0;
            }
            runColour = colour;
            runLength++;
        }
        if (runLength > 0) {
            appendRun(out, runColour, runLength);
        }
        return out.isEmpty() ? " " : out.toString();
    }

    private static void appendRun(StringBuilder out, int colour, int length) {
        String pixels = PIXEL.repeat(length);
        if (colour < 0) {
            out.append(" ".repeat(length));
            return;
        }
        out.append("<color:#").append(String.format(Locale.ROOT, "%06X", colour)).append('>')
                .append(pixels).append("</color>");
    }

    /** The pixel of the front view at ({@code x},{@code y}); negative alpha means transparent. */
    static int sourceArgb(BufferedImage skin, SkinKind kind, int x, int y) {
        return switch (kind) {
            case HEAD -> head(skin, x, y);
            case BUST, BODY -> {
                if (y < 8) {
                    yield x >= 4 && x < 12 ? head(skin, x - 4, y) : 0;
                }
                if (y < 20) {
                    if (x < 4) {
                        yield pixel(skin, RIGHT_ARM_X + x, ARM_Y + (y - 8));
                    }
                    if (x < 12) {
                        yield pixel(skin, TORSO_X + (x - 4), TORSO_Y + (y - 8));
                    }
                    yield pixel(skin, LEFT_ARM_X + (x - 12), LEFT_ARM_Y + (y - 8));
                }
                if (kind == SkinKind.BUST) {
                    yield 0;
                }
                if (x >= 4 && x < 8) {
                    yield pixel(skin, RIGHT_LEG_X + (x - 4), LEG_Y + (y - 20));
                }
                if (x >= 8 && x < 12) {
                    yield pixel(skin, LEFT_LEG_X + (x - 8), LEFT_LEG_Y + (y - 20));
                }
                yield 0;
            }
        };
    }

    /** Head base face plus the hat overlay where the overlay is opaque. */
    private static int head(BufferedImage skin, int x, int y) {
        int overlay = pixel(skin, HAT_X + x, HAT_Y + y);
        return alpha(overlay) >= OPAQUE ? overlay : pixel(skin, HEAD_X + x, HEAD_Y + y);
    }

    private static int pixel(BufferedImage skin, int x, int y) {
        if (x < 0 || y < 0 || x >= skin.getWidth() || y >= skin.getHeight()) {
            return 0;
        }
        return skin.getRGB(x, y);
    }

    private static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    /**
     * A recognisable default skin for offline-mode servers and failed lookups. Only the front faces are filled;
     * the rest stays transparent like an unused skin area.
     */
    static BufferedImage defaultSkin() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(image, HEAD_X, HEAD_Y, 8, 8, 0xFFC68642); // face
        fill(image, HEAD_X, HEAD_Y, 8, 2, 0xFF3B2414); // hair
        fill(image, HEAD_X + 1, HEAD_Y + 4, 2, 1, 0xFF3B2414); // eyes
        fill(image, HEAD_X + 5, HEAD_Y + 4, 2, 1, 0xFF3B2414);
        fill(image, TORSO_X, TORSO_Y, 8, 12, 0xFF00A8A8); // shirt
        fill(image, RIGHT_ARM_X, ARM_Y, 4, 12, 0xFF00A8A8);
        fill(image, LEFT_ARM_X, LEFT_ARM_Y, 4, 12, 0xFF00A8A8);
        fill(image, RIGHT_ARM_X, ARM_Y + 10, 4, 2, 0xFFC68642); // hands
        fill(image, LEFT_ARM_X, LEFT_ARM_Y + 10, 4, 2, 0xFFC68642);
        fill(image, RIGHT_LEG_X, LEG_Y, 4, 12, 0xFF3B3B8F); // trousers
        fill(image, LEFT_LEG_X, LEFT_LEG_Y, 4, 12, 0xFF3B3B8F);
        fill(image, RIGHT_LEG_X, LEG_Y + 10, 4, 2, 0xFF2B2B2B); // shoes
        fill(image, LEFT_LEG_X, LEFT_LEG_Y + 10, 4, 2, 0xFF2B2B2B);
        return image;
    }

    private static void fill(BufferedImage image, int x, int y, int width, int height, int argb) {
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                image.setRGB(x + dx, y + dy, argb);
            }
        }
    }
}
