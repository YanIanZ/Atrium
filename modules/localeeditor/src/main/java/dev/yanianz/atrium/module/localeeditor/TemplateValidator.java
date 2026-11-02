package dev.yanianz.atrium.module.localeeditor;

import dev.yanianz.atrium.core.message.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Validates admin-written MiniMessage before it reaches a language file, and renders it for the preview.
 *
 * <p>MiniMessage is lenient by default: a tag it cannot resolve is printed as literal text or silently dropped, so a
 * plain {@code deserialize} accepts {@code <gradient:red>} or {@code <#gggggg>} without a word. The validator
 * therefore resolves the real tags itself and records every failure (with the parser's own message) while the parse
 * runs; the text is refused when anything was recorded.
 *
 * <p>Templates contain per-call placeholders such as {@code <player>} that are only resolved when a message is used.
 * Those names are supplied by the validator instead, so a legitimate placeholder is never mistaken for a typo.
 */
final class TemplateValidator {
    private TemplateValidator() {}

    /** The tags every template may use: the standard set plus {@code <prefix>} and the theme palette. */
    static TagResolver known(Messages messages) {
        return TagResolver.resolver(TagResolver.standard(), messages.globalTags());
    }

    /** {@code null} when the template is valid; otherwise the parser's finding, shown to the admin as plain text. */
    static String error(String template, MiniMessage mini, TagResolver known) {
        if (template == null) {
            return "The text is empty.";
        }
        List<String> problems = new ArrayList<>();
        TagResolver placeholders = new TagResolver() {
            @Override
            public Tag resolve(String name, ArgumentQueue arguments,
                    net.kyori.adventure.text.minimessage.Context context) {
                if (name.startsWith("#") && !known.has(name)) {
                    problems.add("Invalid colour '" + name + "'. Use a named colour or #RRGGBB.");
                }
                return Tag.inserting(Component.empty());
            }

            @Override
            public boolean has(String name) {
                return true;
            }
        };
        TagResolver checking = new TagResolver() {
            @Override
            public Tag resolve(String name, ArgumentQueue arguments,
                    net.kyori.adventure.text.minimessage.Context context) {
                // A closing tag is resolved with an empty queue; its failure is not a problem of the template.
                boolean hasArguments = arguments.hasNext();
                try {
                    Tag tag = known.resolve(name, arguments, context);
                    if (tag == null && hasArguments) {
                        problems.add("Invalid MiniMessage tag <" + name + ">");
                    }
                    return tag;
                } catch (ParsingException e) {
                    if (hasArguments) {
                        problems.add(e.getMessage() == null
                                ? "Invalid MiniMessage tag <" + name + ">"
                                : e.getMessage());
                    }
                    return null;
                }
            }

            @Override
            public boolean has(String name) {
                return known.has(name);
            }
        };
        try {
            // The checking resolver is passed last so it wins for every real tag; placeholders are the fallback.
            mini.deserialize(template, TagResolver.resolver(placeholders, checking));
        } catch (RuntimeException e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        return problems.isEmpty() ? null : problems.getFirst();
    }

    static Optional<String> validate(String template, MiniMessage mini, TagResolver known) {
        return Optional.ofNullable(error(template, mini, known));
    }

    /** Parses a stored template with placeholders kept visible and real tags as lenient as players see them. */
    static Component render(String template, MiniMessage mini, TagResolver known) {
        TagResolver placeholders = new TagResolver() {
            @Override
            public Tag resolve(String name, ArgumentQueue arguments,
                    net.kyori.adventure.text.minimessage.Context context) {
                return Tag.inserting(Component.text("<" + name + ">"));
            }

            @Override
            public boolean has(String name) {
                return !name.startsWith("#");
            }
        };
        return mini.deserialize(template, TagResolver.resolver(placeholders, known));
    }
}
