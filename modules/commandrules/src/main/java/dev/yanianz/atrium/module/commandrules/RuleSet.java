package dev.yanianz.atrium.module.commandrules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Validated, canonically keyed rule lookup. Pure so the matching rules can be unit tested. */
public final class RuleSet {
    private static final RuleSet EMPTY = new RuleSet(Map.of(), Map.of());

    private final Map<String, CommandRule> rules;
    /** Extra labels declared by {@code aliases:}; an alias always maps to the same rule. */
    private final Map<String, CommandRule> aliasKeys;

    private RuleSet(Map<String, CommandRule> rules, Map<String, CommandRule> aliasKeys) {
        this.rules = rules;
        this.aliasKeys = aliasKeys;
    }

    public static RuleSet empty() {
        return EMPTY;
    }

    /**
     * Converts config entries into rules. Invalid values are clamped or skipped with a warning instead of
     * failing the whole module: a single typo must not disable command enforcement for the server.
     */
    public static RuleSet build(List<CommandRulesConfig.Rule> definitions,
            Function<String, String> canonicalizer, Consumer<String> warn) {
        if (definitions == null || definitions.isEmpty()) {
            return empty();
        }
        Map<String, CommandRule> map = new LinkedHashMap<>();
        Map<String, CommandRule> aliasIndex = new LinkedHashMap<>();
        for (CommandRulesConfig.Rule definition : definitions) {
            if (definition == null || !definition.active) {
                continue;
            }
            String command = definition.command == null ? "" : definition.command.strip();
            if (command.isEmpty()) {
                warn.accept("a rule has an empty 'command'");
                continue;
            }
            String root = CommandLabels.canonicalize(command, canonicalizer);
            if (root.isEmpty()) {
                warn.accept("cannot resolve command '" + command + "'");
                continue;
            }
            if (map.containsKey(root)) {
                warn.accept("duplicate rule for '/" + root + "' ignored");
                continue;
            }
            double money = Double.isFinite(definition.cost) ? definition.cost : 0;
            if (money < 0) {
                warn.accept("negative cost for '/" + root + "' treated as 0");
                money = 0;
            }
            int levels = Math.max(0, definition.expLevels);
            int cooldown = definition.cooldownSeconds;
            if (cooldown < -1) {
                warn.accept("invalid cooldown " + cooldown + " for '/" + root + "'; using 0");
                cooldown = 0;
            }
            int warmup = Math.max(0, definition.warmupSeconds);
            Set<String> worlds = definition.blockedWorlds == null
                    ? Set.of()
                    : definition.blockedWorlds.stream()
                            .filter(world -> world != null && !world.isBlank())
                            .map(world -> world.strip().toLowerCase(Locale.ROOT))
                            .collect(Collectors.toUnmodifiableSet());
            String permission = definition.permission == null ? "" : definition.permission.strip();
            Set<String> aliases = new LinkedHashSet<>();
            if (definition.aliases != null) {
                for (String alias : definition.aliases) {
                    if (alias == null || alias.isBlank()) {
                        continue;
                    }
                    String key = CommandLabels.canonicalize(alias, canonicalizer);
                    if (key.isEmpty() || key.equals(root)) {
                        continue;
                    }
                    if (map.containsKey(key) || aliasIndex.containsKey(key)) {
                        warn.accept("alias '" + alias + "' of '/" + root + "' is already used by another rule");
                        continue;
                    }
                    aliases.add(key);
                }
            }
            CommandRule rule = new CommandRule(root, command, BigDecimal.valueOf(money), levels, cooldown, warmup,
                    worlds, permission, definition.confirmCost, aliases);
            map.put(root, rule);
            for (String alias : aliases) {
                aliasIndex.put(alias, rule);
            }
        }
        return new RuleSet(Map.copyOf(map), Map.copyOf(aliasIndex));
    }

    public Optional<CommandRule> find(String canonicalRoot) {
        if (canonicalRoot == null) {
            return Optional.empty();
        }
        CommandRule rule = rules.get(canonicalRoot);
        if (rule == null) {
            rule = aliasKeys.get(canonicalRoot);
        }
        return Optional.ofNullable(rule);
    }

    /**
     * Match with the live command map. Rules are canonically keyed when loaded, but on a cold start the
     * server may not have registered every command yet; resolving the configured root again on a miss
     * means an alias written in the config still matches after the command map became complete.
     */
    public Optional<CommandRule> find(String canonicalRoot, Function<String, String> canonicalizer) {
        if (canonicalRoot == null) {
            return Optional.empty();
        }
        CommandRule direct = rules.get(canonicalRoot);
        if (direct != null) {
            return Optional.of(direct);
        }
        CommandRule alias = aliasKeys.get(canonicalRoot);
        if (alias != null) {
            return Optional.of(alias);
        }
        for (CommandRule rule : all()) {
            if (CommandLabels.canonicalize(rule.configuredRoot(), canonicalizer).equals(canonicalRoot)
                    || rule.aliases().stream()
                            .anyMatch(key -> CommandLabels.canonicalize(key, canonicalizer)
                                    .equals(canonicalRoot))) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    public Collection<CommandRule> all() {
        List<CommandRule> list = new ArrayList<>(rules.values());
        list.sort((a, b) -> a.root().compareTo(b.root()));
        return List.copyOf(list);
    }

    public int size() {
        return rules.size();
    }
}
