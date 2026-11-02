package dev.yanianz.atrium.module.sourbycraft;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Describes how money relates to profiles: the SourbyCraft purse backend (SSB-AuroraProfiles,
 * {@code economy.backend}) and the Vault economy provider. Atrium's own economy is never profile-keyed.
 * Pure, for the startup log and {@code /atrium profile}.
 */
final class EconomyMode {
    /** @param text one line; {@code atriumBehindPurse} = the purse writes balances into Atrium through Vault */
    record Result(String text, boolean atriumBehindPurse) {}

    private EconomyMode() {}

    /**
     * @param multiprofile  MultiProfile enabled
     * @param purseBackend  {@code ProfilePurse.backend()} or null when no purse service is registered
     * @param vaultProvider name of the plugin that provides the Vault economy, or null
     */
    static Result describe(boolean multiprofile, @Nullable String purseBackend, @Nullable String vaultProvider) {
        return describe(multiprofile, true, purseBackend, vaultProvider);
    }

    /**
     * @param purseApi whether the server's API declares a purse service ({@code ProfilePurse}: extended local
     *                 API builds only; the official 26.2 API has none)
     */
    static Result describe(boolean multiprofile, boolean purseApi, @Nullable String purseBackend,
            @Nullable String vaultProvider) {
        String provider = vaultProvider == null ? "none" : vaultProvider;
        if (!multiprofile) {
            return new Result("MultiProfile off; balances are per account (Vault provider: " + provider + ")", false);
        }
        if (!purseApi) {
            return new Result("this SourbyCraft API has no profile purse service, so Atrium cannot see one: balances "
                    + "are per account, shared by all profiles, unless another plugin swaps them through Vault "
                    + "(Vault provider: " + provider + ")", false);
        }
        if (purseBackend == null) {
            return new Result("no profile purse (SSB-AuroraProfiles not installed): balances are per account, shared by "
                    + "all profiles (Vault provider: " + provider + ")", false);
        }
        String backend = purseBackend.toLowerCase(Locale.ROOT);
        if (backend.startsWith("vault")) {
            boolean atrium = "Atrium".equalsIgnoreCase(vaultProvider);
            if (atrium) {
                return new Result("profile purse -> Vault -> Atrium: the purse swaps the balance on every switch; "
                        + "Atrium stores it per account and is not profile-keyed", true);
            }
            return new Result("profile purse -> Vault -> " + provider + "; Atrium economy is not used by the purse", false);
        }
        if (backend.startsWith("native")) {
            return new Result("profile purse is native (its own per-profile currency); the Atrium economy is "
                    + "separate and per account", false);
        }
        if (backend.startsWith("essentials")) {
            return new Result("profile purse -> Essentials; the Atrium economy is separate and per account", false);
        }
        return new Result("profile purse backend '" + purseBackend + "'; the Atrium economy is per account", false);
    }
}
