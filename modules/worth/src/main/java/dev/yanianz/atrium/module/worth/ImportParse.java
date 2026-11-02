package dev.yanianz.atrium.module.worth;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** The parsed contents of all files of one source. */
record ImportParse(List<ImportCandidate> candidates, List<ImportSkip> skipped) {
    ImportParse {
        candidates = List.copyOf(candidates);
        skipped = List.copyOf(skipped);
    }

    static final class Builder {
        private final List<ImportCandidate> candidates = new ArrayList<>();
        private final List<ImportSkip> skipped = new ArrayList<>();

        void candidate(ImportCandidate candidate) {
            candidates.add(candidate);
        }

        void skip(String origin, String reason, String detail) {
            skipped.add(new ImportSkip(origin, reason, detail));
        }

        ImportParse build() {
            return new ImportParse(candidates, skipped);
        }
    }

    static ConfigurationNode yaml(String text) throws Exception {
        return YamlConfigurationLoader.builder().buildAndLoadString(text);
    }

    static Optional<BigDecimal> price(ConfigurationNode node) {
        return ImportText.number(node);
    }
}
