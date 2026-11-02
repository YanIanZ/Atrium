package dev.yanianz.atrium.module.sourbycraft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EconomyModeTest {
    @Test
    void vaultToAtriumIsFlagged() {
        EconomyMode.Result result = EconomyMode.describe(true, "vault", "Atrium");
        assertTrue(result.atriumBehindPurse());
        assertTrue(result.text().contains("not profile-keyed"));
    }

    @Test
    void otherModesAreNotAtriumBehindThePurse() {
        assertFalse(EconomyMode.describe(true, "vault", "EssentialsX").atriumBehindPurse());
        assertFalse(EconomyMode.describe(true, "native", "Atrium").atriumBehindPurse());
        assertFalse(EconomyMode.describe(true, "essentials", null).atriumBehindPurse());
        assertFalse(EconomyMode.describe(true, null, "Atrium").atriumBehindPurse());
        assertFalse(EconomyMode.describe(false, "vault", "Atrium").atriumBehindPurse());
        assertTrue(EconomyMode.describe(true, null, "Atrium").text().contains("SSB-AuroraProfiles"));
    }

    @Test
    void officialApiWithoutPurseServiceSaysSo() {
        EconomyMode.Result result = EconomyMode.describe(true, false, null, "Atrium");
        assertFalse(result.atriumBehindPurse());
        assertTrue(result.text().contains("no profile purse service"));
    }
}
