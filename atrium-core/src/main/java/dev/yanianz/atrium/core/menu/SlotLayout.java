package dev.yanianz.atrium.core.menu;

import java.util.ArrayList;
import java.util.List;

/**
 * Slot arithmetic for chest menus (9 columns, 1-6 rows). Rows and columns are 0-based.
 *
 * <p>Standard navigation bar (bottom row): back {@code col 0}, previous {@code col 3}, page indicator
 * {@code col 4}, next {@code col 5}, close {@code col 8}. Menus built with {@link PagedMenu} and
 * {@link ConfirmMenu} use it, so every Atrium menu feels the same.
 */
public final class SlotLayout {
    public static final int COLUMNS = 9;
    public static final int MAX_ROWS = 6;
    public static final int BACK_COLUMN = 0;
    public static final int PREVIOUS_COLUMN = 3;
    public static final int PAGE_COLUMN = 4;
    public static final int NEXT_COLUMN = 5;
    public static final int CLOSE_COLUMN = 8;

    private SlotLayout() {}

    public static int checkRows(int rows) {
        if (rows < 1 || rows > MAX_ROWS) {
            throw new IllegalArgumentException("A chest menu has 1-6 rows, got " + rows);
        }
        return rows;
    }

    public static int slot(int row, int column) {
        if (row < 0 || row >= MAX_ROWS || column < 0 || column >= COLUMNS) {
            throw new IllegalArgumentException("Invalid slot row=" + row + " column=" + column);
        }
        return row * COLUMNS + column;
    }

    public static int row(int slot) {
        return slot / COLUMNS;
    }

    public static int column(int slot) {
        return slot % COLUMNS;
    }

    /** Every slot of the rows {@code fromRow..toRow} (inclusive), left to right, top to bottom. */
    public static int[] rows(int fromRow, int toRow) {
        List<Integer> slots = new ArrayList<>();
        for (int row = fromRow; row <= toRow; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                slots.add(slot(row, column));
            }
        }
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    /** All slots except the bottom row: the content area of a menu with a navigation bar. */
    public static int[] contentAboveNavigation(int rows) {
        checkRows(rows);
        if (rows == 1) {
            return new int[0];
        }
        return rows(0, rows - 2);
    }

    /**
     * The inner rectangle of a framed menu: rows {@code 1..rows-2}, columns 1-7. The top row and the outer
     * columns are the frame; the bottom row is both frame and navigation bar. For 6 rows that is 28 slots,
     * for 3 rows 7 slots. Menus with fewer than 3 rows fall back to {@link #contentAboveNavigation(int)}.
     */
    public static int[] framedContent(int rows) {
        checkRows(rows);
        if (rows < 3) {
            return contentAboveNavigation(rows);
        }
        List<Integer> slots = new ArrayList<>();
        for (int row = 1; row <= rows - 2; row++) {
            for (int column = 1; column < COLUMNS - 1; column++) {
                slots.add(slot(row, column));
            }
        }
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Slot of a column in the bottom (navigation) row. */
    public static int bottom(int rows, int column) {
        return slot(checkRows(rows) - 1, column);
    }

    public static int size(int rows) {
        return checkRows(rows) * COLUMNS;
    }
}
