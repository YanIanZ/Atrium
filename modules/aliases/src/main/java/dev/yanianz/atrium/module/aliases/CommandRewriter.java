package dev.yanianz.atrium.module.aliases;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Command replacement rules: a typed command whose words start with {@code from} is rewritten to {@code to}
 * plus the rest of the line. Deterministic (no cache, longest match wins) and applied once. Pure.
 *
 * <p>Three kinds of rule exist:
 * <ul>
 *   <li><b>word</b> (default): {@code from} must be the whole command name, or the first words of it.</li>
 *   <li><b>prefix</b> ({@code prefix-match: true}): the first word must <em>start</em> with {@code from}, so
 *       {@code from: home} matches {@code /homebase}. The rest of the word stays part of the remainder.</li>
 *   <li><b>catch-all</b> ({@code catch-all: true}): applied only when no other rule matched. {@code from} is
 *       ignored (write {@code "*"}); the remainder is the whole typed line.</li>
 * </ul>
 *
 * <p>{@code to} may contain {@code {args}}, which is replaced by the remainder. Without it the remainder is
 * appended, exactly like before. A rewritten command is never rewritten again.
 */
final class CommandRewriter {
    record Rule(String from, String to, String permission, boolean prefixMatch, boolean catchAll) {}

    private final List<Rule> rules;
    private final List<Rule> catchAll;

    private CommandRewriter(List<Rule> rules, List<Rule> catchAll) {
        this.rules = List.copyOf(rules);
        this.catchAll = List.copyOf(catchAll);
    }

    static final CommandRewriter EMPTY = new CommandRewriter(List.of(), List.of());

    /** Builds the rules; invalid ones are reported to {@code problems} and skipped. */
    static CommandRewriter of(List<AliasesConfig.ReplacementRule> definitions, List<String> problems) {
        List<Rule> rules = new ArrayList<>();
        List<Rule> catchAll = new ArrayList<>();
        for (AliasesConfig.ReplacementRule def : definitions == null ? List.<AliasesConfig.ReplacementRule>of() : definitions) {
            boolean prefixMatch = def.prefixMatch;
            boolean catchAllRule = def.catchAll;
            String from = catchAllRule ? "" : normalize(def.from).toLowerCase(Locale.ROOT);
            String to = normalize(def.to);
            if (catchAllRule) {
                if (to.isEmpty()) {
                    problems.add("catch-all rule: 'to' must not be empty");
                    continue;
                }
                catchAll.add(new Rule("", to, permissionOf(def), false, true));
                continue;
            }
            if (from.isEmpty() || to.isEmpty()) {
                problems.add("replacement '" + def.from + "' -> '" + def.to + "': from and to must not be empty");
                continue;
            }
            // A prefix rule with the same target still changes the line (/homebase -> /home base), so
            // only a whole-word rule that maps onto itself is rejected as a typo.
            if (!prefixMatch && from.equals(to.toLowerCase(Locale.ROOT))) {
                problems.add("replacement '" + from + "' rewrites to itself");
                continue;
            }
            rules.add(new Rule(from, to, permissionOf(def), prefixMatch, false));
        }
        return new CommandRewriter(rules, catchAll);
    }

    private static String permissionOf(AliasesConfig.ReplacementRule def) {
        return def.permission == null ? "" : def.permission.strip();
    }

    int size() {
        return rules.size() + catchAll.size();
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
        if ((rules.isEmpty() && catchAll.isEmpty()) || message == null || !message.startsWith("/")) {
            return Optional.empty();
        }
        String body = normalize(message);
        if (body.isEmpty()) {
            return Optional.empty();
        }
        String lower = body.toLowerCase(Locale.ROOT);
        Rule best = null;
        String bestRest = "";
        boolean bestWordBoundary = false;
        for (Rule rule : rules) {
            boolean exact = lower.equals(rule.from());
            boolean wordBoundary = exact || lower.startsWith(rule.from() + " ");
            if (!wordBoundary && !(rule.prefixMatch() && lower.startsWith(rule.from()))) {
                continue;
            }
            if (!rule.permission().isEmpty() && !hasPermission.test(rule.permission())) {
                continue;
            }
            // A whole-word match always beats a prefix match; between equals, the longest rule wins.
            boolean better = best == null
                    || (wordBoundary && !bestWordBoundary)
                    || (wordBoundary == bestWordBoundary && rule.from().length() > best.from().length());
            if (better) {
                best = rule;
                bestWordBoundary = wordBoundary;
                bestRest = body.substring(rule.from().length()).strip();
            }
        }
        if (best == null) {
            for (Rule rule : catchAll) {
                if (rule.permission().isEmpty() || hasPermission.test(rule.permission())) {
                    best = rule;
                    bestRest = body;
                    break;
                }
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        return Optional.of("/" + expand(best.to(), bestRest));
    }

    /** Replaces {@code {args}} in the target, or appends the remainder when there is no marker. */
    private static String expand(String to, String rest) {
        if (to.contains("{args}")) {
            return to.replace("{args}", rest).strip().replaceAll("\\s+", " ");
        }
        return rest.isEmpty() ? to : to + " " + rest;
    }
}
