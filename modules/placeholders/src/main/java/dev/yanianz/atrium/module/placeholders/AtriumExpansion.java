package dev.yanianz.atrium.module.placeholders;

import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * The {@code %atrium_*%} PlaceholderAPI expansion.
 *
 * <p>This is the only class that references PlaceholderAPI. It is loaded lazily and only after the
 * module checked that the plugin is enabled, so a server without PlaceholderAPI never resolves the
 * classes and cannot fail with {@link NoClassDefFoundError}.
 *
 * <p>{@link #onRequest(OfflinePlayer, String)} may run on any thread. It only reads immutable cache
 * entries and thread-safe service methods; see {@link PlaceholdersModule#resolve}.
 */
public final class AtriumExpansion extends PlaceholderExpansion implements PlaceholderHook {
    private final PlaceholdersModule module;
    private final String version;

    AtriumExpansion(PlaceholdersModule module, String version) {
        this.module = module;
        this.version = version;
    }

    @Override
    public String getIdentifier() {
        return "atrium";
    }

    @Override
    public String getAuthor() {
        return "yanianz";
    }

    @Override
    public String getVersion() {
        return version;
    }

    /** The expansion survives a PlaceholderAPI {@code /papi reload}. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        return module.resolve(player, params == null ? "" : params.toLowerCase(Locale.ROOT).trim());
    }
}
