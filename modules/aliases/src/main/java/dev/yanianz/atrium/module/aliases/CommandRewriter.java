package dev.yanianz.atrium.module.aliases;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Command replacement rules: a typed command whose words start with {@code from} is rewritten to {@code to} plus
 * the rest of the line. Deterministic (longest matching prefix wins, no cache) and applied once. Pure.
 */
final class CommandRewriter {
    record Rule(String from, String to, String permission) {}

    private final List<Rule> rules;

    private CommandRewriter(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    static final CommandRewriter EMPTY = new CommandRewriter(List.of());

    /** Builds the rules; invalid ones are reported to {@code problems} and skipped. */
    static CommandRewriter of(List<AliasesConfig.ReplacementRule> definitions, List<String> problems) {
        List<Rule> rules = new ArrayList<>();
        for (AliasesConfig.ReplacementRule def : definitions == null ? List.<AliasesConfig.ReplacementRule>of() : definitions) {
            String from = normalize(def.from);
            String to = normalize(def.to);
            if (from.isEmpty() || to.isEmpty()) {
                problems.add("replacement '" + def.from + "' -> '" + def.to + "': from and to must not be empty");
                continue;
            }
            if (from.equals(to.toLowerCase(Locale.ROOT))) {
                problems.add("replacement '" + from + "' rewrites to itself");
                continue;
            }
            rules.add(new Rule(from.toLowerCase(Locale.ROOT), to, def.permission == null ? "" : def.permission.strip()));
        }
        return new CommandRewriter(rules);
    }

    int size() {
        return rules.size();
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String out = text.strip();
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        return out.strip().replaceAll("\\s+", " ");
    }

    /** {@code message} as typed ({@code /cmd args}). Empty when no rule applies. */
    Optional<String> rewrite(String message, Predicate<String> hasPermission) {
        if (rules.isEmpty() || message == null || !message.startsWith("/")) {
            return Optional.empty();
        }
        String body = normalize(message);
        String lower = body.toLowerCase(Locale.ROOT);
        Rule best = null;
        for (Rule rule : rules) {
            boolean matches = lower.equals(rule.from()) || lower.startsWith(rule.from() + " ");
            if (matches && (best == null || rule.from().length() > best.from().length())
                    && (rule.permission().isEmpty() || hasPermission.test(rule.permission()))) {
                best = rule;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        return Optional.of("/" + best.to() + body.substring(best.from().length()));
    }
}
