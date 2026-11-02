package dev.yanianz.atrium.module.moderation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Warning escalation. A rule with threshold {@code n} fires when the number of active warnings goes from
 * below {@code n} to {@code n} or more. It therefore fires once per crossing: a fourth warning does not
 * re-run the "3 warnings" rule, but after warnings expired and the count climbs back to 3 it fires again.
 */
final class Escalation {
    private Escalation() {}

    static <R> List<R> triggered(List<R> rules, ToIntFunction<R> threshold, int before, int after) {
        List<R> fired = new ArrayList<>();
        if (after <= before) {
            return fired;
        }
        for (R rule : rules) {
            int n = threshold.applyAsInt(rule);
            if (n > 0 && before < n && n <= after) {
                fired.add(rule);
            }
        }
        fired.sort(Comparator.comparingInt(threshold));
        return fired;
    }

    /** Replaces {@code {player}}, {@code {uuid}} and {@code {count}} in an escalation command. */
    static String expand(String command, String player, String uuid, int count) {
        String text = command.strip();
        while (text.startsWith("/")) {
            text = text.substring(1);
        }
        return text.replace("{player}", player).replace("{uuid}", uuid).replace("{count}", Integer.toString(count));
    }
}
