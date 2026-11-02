package dev.yanianz.atrium.module.itemtools;

import java.util.function.IntPredicate;

/**
 * The pure decision behind /getbook. A copy is never created from nothing: the player must own an empty
 * book and quill, which is replaced by the copy. Without that rule the command minted a free writable book
 * on every use.
 */
final class BookCopy {
    enum Outcome { NOT_BOOK, DENIED, NO_BLANK_BOOK, COPY }

    private BookCopy() {
    }

    /**
     * @param holdsWrittenBook the main hand holds a written book with readable book data
     * @param ownBook the player is the book's author
     * @param mayCopyOthers the player may copy books written by others
     * @param blankSlot the inventory slot of an empty book and quill, or -1 when there is none
     */
    static Outcome decide(boolean holdsWrittenBook, boolean ownBook, boolean mayCopyOthers, int blankSlot) {
        if (!holdsWrittenBook) {
            return Outcome.NOT_BOOK;
        }
        if (!ownBook && !mayCopyOthers) {
            return Outcome.DENIED;
        }
        return blankSlot < 0 ? Outcome.NO_BLANK_BOOK : Outcome.COPY;
    }

    /** The first of {@code slots} slots holding an empty book and quill, or -1. */
    static int firstBlank(int slots, IntPredicate isBlankWritableBook) {
        for (int slot = 0; slot < slots; slot++) {
            if (isBlankWritableBook.test(slot)) {
                return slot;
            }
        }
        return -1;
    }
}
