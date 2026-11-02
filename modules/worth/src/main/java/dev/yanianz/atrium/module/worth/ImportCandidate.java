package dev.yanianz.atrium.module.worth;

import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** One parsed price line of another plugin's file, before it is matched against Atrium's price entries. */
record ImportCandidate(
        String origin,
        String material,
        @Nullable List<String> customModelData,
        @Nullable String name,
        BigDecimal unitPrice,
        BigDecimal totalPrice,
        int amount) {

    ImportCandidate {
        customModelData = customModelData == null || customModelData.isEmpty() ? null : List.copyOf(customModelData);
    }
}
