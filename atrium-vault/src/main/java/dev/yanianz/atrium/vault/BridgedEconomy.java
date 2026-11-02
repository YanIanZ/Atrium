package dev.yanianz.atrium.vault;

/**
 * Marker for the adapters Atrium Vault registers itself to expose an economy under the other Vault API version.
 * A bridge is never a candidate for another bridge, so the two adapters can never delegate to each other.
 */
public interface BridgedEconomy {
    /** For example {@code "bridge v1->v2 of EssentialsX"}. */
    String bridgeDescription();
}
