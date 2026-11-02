package dev.yanianz.atrium.module.dialogs;

import dev.yanianz.atrium.module.dialogs.DialogDefinition.BoolInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.InputSpec;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.NumberInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.OptionInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.TextInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Server-side validation of a dialog response. The client can send anything (a modified client is not bound by
 * {@code max_length}, the option list or the slider range), so every value is checked against the definition
 * before it becomes an action variable:
 * <ul>
 *   <li>text: present, at most {@code max-length} characters, fully matches the input's pattern;</li>
 *   <li>option: the id is one of the declared ids (exact match);</li>
 *   <li>bool: present; becomes the admin's {@code on-true}/{@code on-false} value;</li>
 *   <li>number: finite and inside the range, snapped to the step and formatted with {@link java.util.Locale#ROOT}.</li>
 * </ul>
 * One bad value rejects the whole response: no action runs with a partial set of variables. Pure, unit tested.
 */
final class InputValidator {
    /** Tolerance for float rounding at the range ends. */
    private static final double EPSILON = 1.0e-4;

    private InputValidator() {}

    /** What the client submitted. Implemented over Paper's {@code DialogResponseView}. */
    interface ResponseValues {
        @Nullable String text(String key);

        @Nullable Boolean bool(String key);

        @Nullable Float number(String key);
    }

    enum Reason {
        MISSING,
        TOO_LONG,
        PATTERN,
        UNKNOWN_OPTION,
        OUT_OF_RANGE
    }

    sealed interface Result permits Accepted, Rejected {}

    /** Validated variables, keyed by input key, in definition order. */
    record Accepted(Map<String, String> variables) implements Result {
        Accepted {
            variables = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        }
    }

    record Rejected(InputSpec input, Reason reason) implements Result {}

    static Result validate(List<InputSpec> inputs, ResponseValues response) {
        Map<String, String> variables = new LinkedHashMap<>();
        for (InputSpec input : inputs) {
            String key = input.key();
            switch (input) {
                case TextInput text -> {
                    String value = response.text(key);
                    Reason reason = checkText(text, value);
                    if (reason != null) {
                        return new Rejected(input, reason);
                    }
                    variables.put(key, value);
                }
                case OptionInput option -> {
                    String value = response.text(key);
                    if (value == null) {
                        return new Rejected(input, Reason.MISSING);
                    }
                    if (!option.hasOption(value)) {
                        return new Rejected(input, Reason.UNKNOWN_OPTION);
                    }
                    variables.put(key, value);
                }
                case BoolInput bool -> {
                    Boolean value = response.bool(key);
                    if (value == null) {
                        return new Rejected(input, Reason.MISSING);
                    }
                    variables.put(key, value ? bool.onTrue() : bool.onFalse());
                }
                case NumberInput number -> {
                    Float value = response.number(key);
                    if (value == null) {
                        return new Rejected(input, Reason.MISSING);
                    }
                    String formatted = number(number, value);
                    if (formatted == null) {
                        return new Rejected(input, Reason.OUT_OF_RANGE);
                    }
                    variables.put(key, formatted);
                }
            }
        }
        return new Accepted(variables);
    }

    /** {@code null} when the text is acceptable for the input. */
    static @Nullable Reason checkText(TextInput input, @Nullable String value) {
        if (value == null) {
            return Reason.MISSING;
        }
        // Length first: bounds the regex work on whatever the client sent.
        if (value.length() > input.maxLength()) {
            return Reason.TOO_LONG;
        }
        if (!input.pattern().matcher(value).matches()) {
            return Reason.PATTERN;
        }
        return null;
    }

    /**
     * The canonical text of a submitted slider value, or {@code null} when it is not finite or outside the range.
     * Whole numbers have no decimal part ({@code "5"}), others at most four decimals ({@code "2.5"}).
     */
    static @Nullable String number(NumberInput input, float submitted) {
        if (!Float.isFinite(submitted)) {
            return null;
        }
        double value = submitted;
        double start = input.start();
        double end = input.end();
        if (value < start - EPSILON || value > end + EPSILON) {
            return null;
        }
        if (input.step() != null) {
            double step = input.step();
            value = start + Math.round((value - start) / step) * step;
        }
        value = Math.max(start, Math.min(end, value));
        BigDecimal decimal = BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        if (decimal.scale() < 0) {
            decimal = decimal.setScale(0, RoundingMode.UNNECESSARY);
        }
        String text = decimal.toPlainString();
        return text.equals("-0") ? "0" : text;
    }
}
