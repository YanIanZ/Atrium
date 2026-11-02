package dev.yanianz.atrium.module.worth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/** A plugin whose sell prices can be copied into Atrium's own price table. */
enum PriceImportSource {
    ECONOMYSHOPGUI("economyshopgui", "EconomyShopGUI", true, "shops"),
    SHOPGUIPLUS("shopguiplus", "ShopGUIPlus", true, "shops"),
    ESSENTIALS("essentials", "Essentials", false, "worth.yml"),
    CMI("cmi", "CMI", false, "Saves/Worth.yml");

    final String id;
    /** Directory under plugins/ that holds the files. */
    final String pluginFolder;
    final boolean directoryOfShops;
    final String relativeFile;

    PriceImportSource(String id, String pluginFolder, boolean directoryOfShops, String relativeFile) {
        this.id = id;
        this.pluginFolder = pluginFolder;
        this.directoryOfShops = directoryOfShops;
        this.relativeFile = relativeFile;
    }

    static Optional<PriceImportSource> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String wanted = raw.strip().toLowerCase(Locale.ROOT);
        for (PriceImportSource source : values()) {
            if (source.id.equals(wanted)) {
                return Optional.of(source);
            }
        }
        return switch (wanted) {
            case "esg", "esgui", "economy-shop-gui" -> Optional.of(ECONOMYSHOPGUI);
            case "sgp", "shopgui", "shopguiplus" -> Optional.of(SHOPGUIPLUS);
            case "ess", "essentialsx" -> Optional.of(ESSENTIALS);
            default -> Optional.empty();
        };
    }

    static List<String> names() {
        return Stream.of(values()).map(s -> s.id).toList();
    }

    /** The files to read; empty when the plugin's folder is not installed. */
    List<Path> files(Path pluginsFolder) throws java.io.IOException {
        Path base = pluginsFolder.resolve(pluginFolder);
        if (directoryOfShops) {
            List<Path> dirs = new ArrayList<>();
            dirs.add(base.resolve(relativeFile));
            if (this == ECONOMYSHOPGUI) {
                // The premium edition keeps its own folder and the same shop layout.
                dirs.add(pluginsFolder.resolve("EconomyShopGUI-Premium").resolve(relativeFile));
            }
            List<Path> found = new ArrayList<>();
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> {
                                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                                return name.endsWith(".yml") || name.endsWith(".yaml");
                            })
                            .sorted()
                            .forEach(found::add);
                }
            }
            return List.copyOf(found);
        }
        Path file = base.resolve(relativeFile);
        if (Files.isRegularFile(file)) {
            return List.of(file);
        }
        // CMI writes Worth.yml; some older versions use worth.yml.
        Path lower = base.resolve(relativeFile.toLowerCase(Locale.ROOT));
        return Files.isRegularFile(lower) ? List.of(lower) : List.of();
    }

    PriceImporter importer() {
        return switch (this) {
            case ECONOMYSHOPGUI -> new ShopFileImporter(this, ShopFileImporter.Format.ECONOMYSHOPGUI);
            case SHOPGUIPLUS -> new ShopFileImporter(this, ShopFileImporter.Format.SHOPGUIPLUS);
            case ESSENTIALS -> new EssentialsImporter();
            case CMI -> new CmiImporter();
        };
    }
}
