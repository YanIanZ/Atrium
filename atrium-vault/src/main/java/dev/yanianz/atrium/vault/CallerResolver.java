package dev.yanianz.atrium.vault;

/** Names the plugin that called into Vault, or returns {@code null} when it cannot be determined. */
@FunctionalInterface
public interface CallerResolver {
    CallerResolver NONE = () -> null;

    String caller();
}
