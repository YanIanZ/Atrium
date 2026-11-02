package dev.yanianz.atrium.module.performance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A soft version number for the update check. Accepts the forms a release tag can have: {@code 1.2.3},
 * {@code v1.2.3}, {@code 26.1.2}, {@code 1.2.3-beta.1}, {@code 1.2.3+build.5}. Pure, so the ordering rules
 * are unit tested instead of being trusted.
 *
 * <p>Ordering: dot-separated numbers are compared with missing parts as 0, so {@code 26.2} equals
 * {@code 26.2.0}. When the numbers are equal a release sorts above a pre-release ({@code 1.0.0 > 1.0.0-rc1}).
 */
final class Version implements Comparable<Version> {
    private final List<Integer> numbers;
    private final String suffix;

    private Version(List<Integer> numbers, String suffix) {
        this.numbers = List.copyOf(numbers);
        this.suffix = suffix;
    }

    static Version parse(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("v") || text.startsWith("V")) {
            text = text.substring(1);
        }
        int plus = text.indexOf('+');
        if (plus >= 0) {
            text = text.substring(0, plus);
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("not a version: " + raw);
        }
        String suffix = "";
        int dash = text.indexOf('-');
        if (dash < 0) {
            dash = text.indexOf('_');
        }
        if (dash >= 0) {
            suffix = text.substring(dash + 1).strip().toLowerCase(Locale.ROOT);
            text = text.substring(0, dash);
        }
        List<Integer> numbers = new ArrayList<>();
        for (String part : text.split("\\.")) {
            numbers.add(number(part));
        }
        if (numbers.isEmpty()) {
            throw new IllegalArgumentException("not a version: " + raw);
        }
        return new Version(numbers, suffix);
    }

    private static int number(String part) {
        String digits = part.strip();
        int end = 0;
        while (end < digits.length() && Character.isDigit(digits.charAt(end))) {
            end++;
        }
        if (end == 0) {
            throw new IllegalArgumentException("not a version part: " + part);
        }
        String value = digits.substring(0, Math.min(end, 9));
        return Integer.parseInt(value);
    }

    /** True when this version is newer than {@code other}. */
    boolean newerThan(Version other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(Version other) {
        int size = Math.max(numbers.size(), other.numbers.size());
        for (int i = 0; i < size; i++) {
            int left = i < numbers.size() ? numbers.get(i) : 0;
            int right = i < other.numbers.size() ? other.numbers.get(i) : 0;
            if (left != right) {
                return Integer.compare(left, right);
            }
        }
        // A release is newer than a pre-release of the same numbers.
        if (suffix.isEmpty() && !other.suffix.isEmpty()) {
            return 1;
        }
        if (!suffix.isEmpty() && other.suffix.isEmpty()) {
            return -1;
        }
        return suffix.compareTo(other.suffix);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Version v && v.numbers.equals(numbers) && v.suffix.equals(suffix);
    }

    @Override
    public int hashCode() {
        return numbers.hashCode() * 31 + suffix.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < numbers.size(); i++) {
            out.append(i == 0 ? "" : ".").append(numbers.get(i));
        }
        return suffix.isEmpty() ? out.toString() : out + "-" + suffix;
    }
}
