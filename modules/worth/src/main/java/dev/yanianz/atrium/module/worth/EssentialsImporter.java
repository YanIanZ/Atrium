package dev.yanianz.atrium.module.worth;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Reads {@code plugins/Essentials/worth.yml}. Its keys are pre-1.13 names without underscores
 * ({@code diamondpickaxe}), so the importer keeps the raw name and the service resolves it against the
 * materials of the running server. Entries that use legacy data values are reported: modern items carry no
 * damage value Atrium could match.
 */
final class EssentialsImporter implements PriceImporter {
    @Override
    public PriceImportSource source() {
        return PriceImportSource.ESSENTIALS;
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
            ConfigurationNode worth = section(root, "worth");
            if (worth.virtual()) {
                out.skip(name, "no-worth-section", "missing 'worth:' at the root");
                continue;
            }
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : worth.childrenMap().entrySet()) {
                String origin = name + " worth." + entry.getKey();
                ConfigurationNode value = entry.getValue();
                if (value.isMap()) {
                    // Essentials on 1.13+: "<item>: {'0': price}" (exactly one key) or the "'*'" wildcard price.
                    ConfigurationNode zero = null;
                    ConfigurationNode any = null;
                    for (Map.Entry<Object, ? extends ConfigurationNode> data : value.childrenMap().entrySet()) {
                        String key = String.valueOf(data.getKey()).trim();
                        if (key.equals("0")) {
                            zero = data.getValue();
                        } else if (key.equals("*")) {
                            any = data.getValue();
                        }
                    }
                    if (value.childrenMap().size() == 1 && zero != null) {
                        value = zero;
                    } else if (any != null) {
                        value = any;
                    }
                }
                if (value.isMap() || value.isList()) {
                    out.skip(origin, "legacy-data",
                            "data values cannot be matched to a modern item: " + entry.getKey());
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
                out.candidate(new ImportCandidate(origin, String.valueOf(entry.getKey()), null, null,
                        price.get(), price.get(), 1));
            }
        }
        return out.build();
    }

    static ConfigurationNode section(ConfigurationNode root, String name) {
        ConfigurationNode direct = root.node(name);
        if (!direct.virtual()) {
            return direct;
        }
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : root.childrenMap().entrySet()) {
            if (name.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return direct;
    }
}
