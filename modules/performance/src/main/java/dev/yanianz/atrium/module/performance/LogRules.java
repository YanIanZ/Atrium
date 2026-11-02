package dev.yanianz.atrium.module.performance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The rules of the console filter. Pure (only {@link Pattern}), so every matching rule is unit tested without
 * a running logger.
 *
 * <p>Safety rules that the filter must never break:
 * <ul>
 *   <li>Atrium's own logger is never filtered: a failure of this plugin must always be visible.</li>
 *   <li>A line that carries a stack trace is only hidden when {@code hide-errors} is on.</li>
 *   <li>The rules only decide; a rule that matches is never applied twice.</li>
 * </ul>
 */
final class LogRules {
    /** Every logger of this plugin starts with this prefix. */
    static final String OWN_LOGGER = "dev.yanianz.atrium";

    enum Action { HIDE, TAG }

    enum Mode { CONTAINS, REGEX }

    record Rule(Mode mode, Pattern pattern, Action action, String tag, String loggerPrefix) {
        boolean applies(String logger, String message) {
            if (!loggerPrefix.isEmpty() && !logger.startsWith(loggerPrefix)) {
                return false;
            }
            return pattern.matcher(message).find();
        }
    }

    /** The first matching rule. {@code action} is null when the line passes unchanged. */
    record Decision(Action action, String tag) {
        static final Decision PASS = new Decision(null, "");

        boolean hide() {
            return action == Action.HIDE;
        }

        boolean tagged() {
            return action == Action.TAG;
        }
    }

    private final boolean enabled;
    private final boolean hideErrors;
    private final List<Rule> rules;

    private LogRules(boolean enabled, boolean hideErrors, List<Rule> rules) {
        this.enabled = enabled;
        this.hideErrors = hideErrors;
        this.rules = List.copyOf(rules);
    }

    static LogRules disabled() {
        return new LogRules(false, false, List.of());
    }

    /** Builds the rules, reporting every entry that cannot be used instead of failing the module. */
    static LogRules build(PerformanceConfig.ConsoleLog config, BiConsumer<String, String> problems) {
        List<Rule> rules = new ArrayList<>();
        int index = 0;
        for (PerformanceConfig.ConsoleRule entry : config == null ? List.<PerformanceConfig.ConsoleRule>of() : config.rules) {
            index++;
            String label = "rule " + index;
            String pattern = entry.pattern == null ? "" : entry.pattern.strip();
            if (pattern.isEmpty()) {
                problems.accept(label, "empty pattern");
                continue;
            }
            Mode mode;
            try {
                mode = Mode.valueOf((entry.mode == null ? "" : entry.mode.strip()).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.accept(label, "mode must be contains or regex");
                continue;
            }
            Action action;
            try {
                action = Action.valueOf((entry.action == null ? "" : entry.action.strip()).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.accept(label, "action must be hide or tag");
                continue;
            }
            Pattern compiled;
            try {
                compiled = mode == Mode.CONTAINS
                        ? Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE)
                        : Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                problems.accept(label, "invalid regex: " + e.getDescription());
                continue;
            }
            String tag = entry.tag == null ? "" : entry.tag.strip();
            rules.add(new Rule(mode, compiled, action, tag, entry.loggerPrefix == null ? "" : entry.loggerPrefix.strip()));
        }
        return new LogRules(config != null && config.enabled, config != null && config.hideErrors, rules);
    }

    boolean enabled() {
        return enabled;
    }

    boolean hideErrors() {
        return hideErrors;
    }

    int ruleCount() {
        return rules.size();
    }

    /** Decides one record. {@code hasThrowable} is true when the record carries a stack trace. */
    Decision decide(String logger, String message, boolean hasThrowable) {
        if (!enabled) {
            return Decision.PASS;
        }
        String name = logger == null ? "" : logger;
        if (name.startsWith(OWN_LOGGER)) {
            return Decision.PASS;
        }
        if (hasThrowable && !hideErrors) {
            return Decision.PASS;
        }
        String text = message == null ? "" : message;
        for (Rule rule : rules) {
            if (rule.applies(name, text)) {
                return new Decision(rule.action(), rule.tag());
            }
        }
        return Decision.PASS;
    }
}
