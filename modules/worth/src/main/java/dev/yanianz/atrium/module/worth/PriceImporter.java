package dev.yanianz.atrium.module.worth;

import java.util.Map;

/** Reads the sell prices of one other plugin. Only parses text: file discovery and writing are elsewhere. */
interface PriceImporter {
    PriceImportSource source();

    /**
     * @param files relative file name ({@code Ores.yml}) to the file's YAML text
     */
    ImportParse parse(Map<String, String> files);
}
