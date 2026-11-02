package dev.yanianz.atrium.vault;

import java.util.List;
import net.milkbowl.vault.economy.Economy;

/** Supplies every registered economy provider, best first (highest service priority first). */
@FunctionalInterface
public interface EconomyCandidates {
    List<Economy> candidates();
}
