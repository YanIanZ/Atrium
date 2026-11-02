package dev.yanianz.atrium.module.localeeditor;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * All file work of the locale editor. Every method here is blocking and must run on the I/O executor.
 *
 * <p>A write re-reads the file <em>inside</em> a lock, applies the edit, then replaces the file atomically
 * (temp file + move in the same directory). The lock covers the whole module (every locale), because the
 * message bundle of one locale used to be reloaded by re-saving the file of <em>every</em> locale: a
 * concurrent edit of en_US and id_ID could lose one of them. The edited locale is now reloaded read-only
 * while the lock is still held.
 */
final class LangStore {
    /** A flattened entry of one language file. */
    record Loaded(String locale, String module, List<LangDocument.Entry> entries) {}

    record SearchHit(String locale, String module, String key, String value) {}

    private final ModuleContext ctx;
    private final ConfigHandle<LocaleEditorConfig> config;
    private final Path langDirectory;
    /** One lock per module; it covers every locale of that module. */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    LangStore(ModuleContext ctx, ConfigHandle<LocaleEditorConfig> config) {
        this.ctx = ctx;
        this.config = config;
        this.langDirectory = ctx.plugin().getDataPath().resolve("lang");
    }

    Path directory() {
        return langDirectory;
    }

    Path file(String locale, String module) {
        return langDirectory.resolve(locale).resolve(module + ".yml");
    }

    // ---- reading ----

    /** Every locale the server may use: the shipped ones plus any folder an admin added. */
    List<String> localeNames() throws IOException {
        LinkedHashSet<String> names = new LinkedHashSet<>(Messages.SHIPPED_LOCALES);
        if (!Files.isDirectory(langDirectory)) {
            return List.copyOf(names);
        }
        try (var stream = Files.list(langDirectory)) {
            stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(KeyPath::validName)
                    .sorted()
                    .forEach(names::add);
        }
        return List.copyOf(names);
    }

    List<String> moduleNames(String locale) throws IOException {
        Path directory = langDirectory.resolve(locale);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".yml"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .filter(KeyPath::validName)
                    .sorted()
                    .toList();
        }
    }

    /** The entries of one file; a missing file falls back to the bundled default, so a key can still be edited. */
    Loaded load(String locale, String module) throws IOException, LangDocument.InvalidDocumentException {
        Path file = file(locale, module);
        String text = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : bundled(locale, module);
        return new Loaded(locale, module, LangDocument.entries(text));
    }

    /** The text currently stored for one key, if any. */
    Optional<String> current(String locale, String module, String key)
            throws IOException, LangDocument.InvalidDocumentException {
        Path file = file(locale, module);
        String text = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : bundled(locale, module);
        return LangDocument.get(text, key);
    }

    /** Case-insensitive search over keys and values of every locale and module, capped at {@code limit}. */
    List<SearchHit> search(String query, int limit) throws IOException {
        String needle = KeyPath.lower(query == null ? "" : query.strip());
        List<SearchHit> hits = new ArrayList<>();
        if (needle.isEmpty() || limit <= 0) {
            return hits;
        }
        for (String locale : localeNames()) {
            for (String module : moduleNames(locale)) {
                Loaded loaded;
                try {
                    loaded = load(locale, module);
                } catch (LangDocument.InvalidDocumentException e) {
                    ctx.logger().warn("Skipping {} in the locale search: {}", file(locale, module), e.getMessage());
                    continue;
                }
                for (LangDocument.Entry entry : loaded.entries()) {
                    if (hits.size() >= limit) {
                        return hits;
                    }
                    if (KeyPath.lower(entry.key()).contains(needle)
                            || KeyPath.lower(entry.value()).contains(needle)) {
                        hits.add(new SearchHit(locale, module, entry.key(), entry.value()));
                    }
                }
            }
        }
        return hits;
    }

    // ---- writing ----

    /** Stores one value. The file is re-read inside the lock, so a concurrent edit is never overwritten blindly. */
    LangDocument.Edit set(String locale, String module, String key, String value)
            throws IOException, LangDocument.InvalidDocumentException {
        return edit(locale, module, current -> LangDocument.set(current, key, value));
    }

    /**
     * Restores the value shipped inside the jar for this locale. Returns empty when the bundle has no such key
     * (for example a locale that ships no defaults of its own).
     */
    Optional<LangDocument.Edit> reset(String locale, String module, String key)
            throws IOException, LangDocument.InvalidDocumentException {
        String bundled = bundled(locale, module);
        if (bundled.isBlank()) {
            return Optional.empty();
        }
        Optional<String> fallback = LangDocument.get(bundled, key);
        if (fallback.isEmpty()) {
            return Optional.empty();
        }
        String value = fallback.get();
        return Optional.of(edit(locale, module, current -> LangDocument.set(current, key, value)));
    }

    private LangDocument.Edit edit(String locale, String module, Change change)
            throws IOException, LangDocument.InvalidDocumentException {
        Path file = file(locale, module);
        // The lock is per module, not per file: editing en_US and id_ID at the same time stays serialised.
        ReentrantLock lock = locks.computeIfAbsent(module, key -> new ReentrantLock());
        lock.lock();
        try {
            String current = Files.exists(file)
                    ? Files.readString(file, StandardCharsets.UTF_8)
                    : bundled(locale, module);
            LangDocument.Edit edit = change.apply(current);
            writeAtomically(file, edit.text());
            if (config.get().reloadAfterWrite) {
                // Only the locale that just changed is read again, and without re-saving any file.
                ctx.messages().reloadLocale(module, locale);
            }
            ctx.logger().info("Locale editor updated {}", file);
            return edit;
        } finally {
            lock.unlock();
        }
    }

    /** A temp file in the same directory is moved over the original, so a crash never leaves half a document. */
    private static void writeAtomically(Path file, String text) throws IOException {
        Path directory = file.getParent();
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, "." + file.getFileName(), ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** The unedited text shipped in the jar, or an empty string when this locale/module has none. */
    String bundled(String locale, String module) throws IOException {
        if (!KeyPath.validName(locale) || !KeyPath.validName(module)) {
            return "";
        }
        try (InputStream in = ctx.plugin().getResource("lang/" + module + "/" + locale + ".yml")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @FunctionalInterface
    private interface Change {
        LangDocument.Edit apply(String yaml) throws LangDocument.InvalidDocumentException;
    }
}
