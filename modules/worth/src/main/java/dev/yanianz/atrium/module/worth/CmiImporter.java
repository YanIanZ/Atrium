package dev.yanianz.atrium.module.worth;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Reads {@code plugins/CMI/Saves/Worth.yml}. Keys are upper-case material names, optionally followed by
 * {@code >VARIANT} (spawner or entity variants). Variant keys and legacy data values cannot be matched by
 * Atrium and are reported; plain materials are imported as one entry each.
 */
final class CmiImporter implements PriceImporter {
    @Override
    public PriceImportSource source() {
        return PriceImportSource.CMI;
    }

    @Override
    public ImportParse parse(Map<String, String> files) {
        ImportParse.Builder out = new ImportParse.Builder();
        List<String> names = new ArrayList<>(files.keySet());
        names.sort(String::compareTo);
        for (String name : names) {
            ConfigurationNode root;
            try {
                root = ImportParse.yaml(files.get(name));
            } catch (Exception e) {
                out.skip(name, "invalid-yaml", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                continue;
            }
            ConfigurationNode worth = EssentialsImporter.section(root, "worth");
            if (worth.virtual()) {
                out.skip(name, "no-worth-section", "missing 'worth:' at the root");
                continue;
            }
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : worth.childrenMap().entrySet()) {
                String key = String.valueOf(entry.getKey());
                String origin = name + " worth." + key;
                if (key.indexOf('>') >= 0) {
                    out.skip(origin, "entity-variant", "variant items are not plain materials");
                    continue;
                }
                ConfigurationNode value = entry.getValue();
                if (value.isMap() || value.isList()) {
                    out.skip(origin, "item-meta", "nested worth data cannot be matched");
                    continue;
                }
                Optional<BigDecimal> price = ImportParse.price(value);
                if (price.isEmpty()) {
                    out.skip(origin, "invalid-price", String.valueOf(value.getString("?")));
                    continue;
                }
                if (price.get().signum() <= 0) {
                    out.skip(origin, "not-sellable", price.get().toPlainString());
                    continue;
                }
                out.candidate(new ImportCandidate(origin, key.toLowerCase(Locale.ROOT), null, null,
                        price.get(), price.get(), 1));
            }
        }
        return out.build();
    }
}
