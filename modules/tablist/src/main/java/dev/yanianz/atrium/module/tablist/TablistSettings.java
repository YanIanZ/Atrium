package dev.yanianz.atrium.module.tablist;

import dev.yanianz.atrium.module.display.Animations;
import dev.yanianz.atrium.module.display.Targeting;
import dev.yanianz.atrium.module.display.TemplateRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Immutable, validated view of {@link TablistConfig}; replaced as a whole on reload. */
record TablistSettings(
        int intervalTicks,
        String header,
        String footer,
        List<HeaderRule> overrides,
        Animations animations,
        boolean setListName,
        List<Group> groups,
        String defaultListName,
        boolean sortByGroup,
        boolean sortByDisplayName) {

    record HeaderRule(Targeting targeting, String header, String footer) {}

    record Group(String name, String permission, String listName) {}

    /** Header/footer pair chosen for one player. */
    record Texts(String header, String footer) {}

    static TablistSettings from(TablistConfig config, BiConsumer<String, String> problems) {
        int interval = clamp(config.updateIntervalTicks, 2, 1200, "update-interval-ticks", problems);
        String header = TemplateRenderer.join(config.header);
        String footer = TemplateRenderer.join(config.footer);
        List<HeaderRule> overrides = new ArrayList<>();
        if (config.overrides != null) {
            for (TablistConfig.HeaderOverride o : config.overrides) {
                if (o == null) {
                    continue;
                }
                overrides.add(new HeaderRule(new Targeting(o.worlds, o.permission),
                        o.header == null || o.header.isEmpty() ? null : TemplateRenderer.join(o.header),
                        o.footer == null || o.footer.isEmpty() ? null : TemplateRenderer.join(o.footer)));
            }
        }
        List<Group> groups = new ArrayList<>();
        if (config.groups != null) {
            for (TablistConfig.Group g : config.groups) {
                if (g == null || g.name == null || !g.name.matches("[A-Za-z0-9_-]+")) {
                    problems.accept("groups", "skipping a group without a valid name (letters, digits, _ and -)");
                    continue;
                }
                if (g.permission == null || g.permission.isBlank()) {
                    problems.accept("groups." + g.name, "group has no permission and would match everyone; skipped");
                    continue;
                }
                groups.add(new Group(g.name, g.permission.trim(), g.listName == null ? "<displayname>" : g.listName));
            }
        }
        return new TablistSettings(interval, header, footer, List.copyOf(overrides),
                Animations.from(config.animations, (name, problem) -> problems.accept("animations." + name, problem)),
                config.setListName, List.copyOf(groups),
                config.defaultListName == null ? "<displayname>" : config.defaultListName,
                config.sortByGroup, config.sortByDisplayName);
    }

    static int clamp(int value, int min, int max, String option, BiConsumer<String, String> problems) {
        if (value < min || value > max) {
            int fixed = Math.clamp(value, min, max);
            problems.accept(option, value + " is outside " + min + "-" + max + "; using " + fixed);
            return fixed;
        }
        return value;
    }

    /** Index of the first group whose permission the player has, or {@code groups.size()} for none. */
    int groupIndex(Predicate<String> hasPermission) {
        for (int i = 0; i < groups.size(); i++) {
            if (hasPermission.test(groups.get(i).permission())) {
                return i;
            }
        }
        return groups.size();
    }

    String listNameFormat(int groupIndex) {
        return groupIndex < groups.size() ? groups.get(groupIndex).listName() : defaultListName;
    }

    Texts texts(String world, Predicate<String> hasPermission) {
        for (HeaderRule o : overrides) {
            if (o.targeting().matches(world, hasPermission)) {
                return new Texts(o.header() == null ? header : o.header(), o.footer() == null ? footer : o.footer());
            }
        }
        return new Texts(header, footer);
    }
}
