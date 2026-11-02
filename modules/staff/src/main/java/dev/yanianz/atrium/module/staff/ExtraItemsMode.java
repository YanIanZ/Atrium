package dev.yanianz.atrium.module.staff;

/** What happens to items picked up or crafted while staff mode is active. */
public enum ExtraItemsMode {
    /** Delete them when staff mode ends. Safest, because nothing can be smuggled out. */
    CLEAR,
    /** Move them into free slots of the restored inventory; anything that does not fit is dropped. */
    KEEP,
    /** Drop all of them at the player's feet when staff mode ends. */
    DROP
}
