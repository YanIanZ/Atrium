package dev.yanianz.atrium.module.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Compiles and resolves the player-selectable prefix/suffix tags. Pure. */
final class ChatTags {
    private ChatTags() {}

    record Definition(String id, String prefix, String suffix, String permission) {
        Definition {
            id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            prefix = prefix == null ? "" : prefix;
            suffix = suffix == null ? "" : suffix;
            permission = permission == null ? "" : permission.trim();
        }
    }

    static List<Definition> compile(ChatConfig.Tags config, Consumer<String> warn) {
        if (config == null || !config.enabled || config.entries == null) {
            return List.of();
        }
        List<Definition> tags = new ArrayList<>();
        for (ChatConfig.TagEntry entry : config.entries) {
            if (entry == null || entry.id == null || entry.id.isBlank()) {
                continue;
            }
            Definition definition = new Definition(entry.id, entry.prefix, entry.suffix, entry.permission);
            if (tags.stream().anyMatch(existing -> existing.id().equals(definition.id()))) {
                warn.accept("Duplicate chat tag id '" + definition.id() + "' ignored");
                continue;
            }
            tags.add(definition);
        }
        return List.copyOf(tags);
    }

    static Optional<Definition> byId(List<Definition> tags, String id) {
        if (tags == null || id == null) {
            return Optional.empty();
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        return tags.stream().filter(tag -> tag.id().equals(wanted)).findFirst();
    }

    /** The selected tag when the player still has its permission; otherwise empty. */
    static Optional<Definition> resolve(List<Definition> tags, String selected, Predicate<String> hasPermission) {
        if (tags == null || tags.isEmpty()) {
            return Optional.empty();
        }
        Optional<Definition> chosen = byId(tags, selected);
        return chosen.isPresent() && allowed(chosen.get(), hasPermission) ? chosen : Optional.empty();
    }

    static boolean allowed(Definition tag, Predicate<String> hasPermission) {
        if (tag == null) {
            return false;
        }
        return tag.permission().isEmpty() || (hasPermission != null && hasPermission.test(tag.permission()));
    }
}
