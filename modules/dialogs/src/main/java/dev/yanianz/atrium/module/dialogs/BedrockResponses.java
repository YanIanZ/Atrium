package dev.yanianz.atrium.module.dialogs;

import dev.yanianz.atrium.module.dialogs.BedrockForm.Dropdown;
import dev.yanianz.atrium.module.dialogs.BedrockForm.Field;
import dev.yanianz.atrium.module.dialogs.BedrockForm.Model;
import dev.yanianz.atrium.module.dialogs.BedrockForm.Slider;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.ButtonSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Turns a Bedrock custom-form submission into the same {@link InputValidator.ResponseValues} the Paper path uses,
 * so both clients go through exactly one validation rule set. Pure and unit tested.
 *
 * <p>The client's payload is keyed by component index. A dropdown answers with an index (never an id), a slider
 * with a float, a toggle with a boolean and an input with text; anything of the wrong type, out of range or
 * missing reads as "absent" and {@link InputValidator} rejects it with {@link InputValidator.Reason#MISSING}
 * instead of throwing (a modified client can send any JSON).
 */
final class BedrockResponses implements InputValidator.ResponseValues {
    private final Map<String, Object> values;

    private BedrockResponses(Map<String, Object> values) {
        this.values = values;
    }

    /**
     * @param submitted component index ({@code Integer}) to raw value, as read from the form response
     */
    static BedrockResponses of(Model model, Map<Integer, Object> submitted) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field field : model.fields()) {
            Object raw = submitted.get(field.index());
            Object canonical = switch (field) {
                case BedrockForm.TextField ignored -> raw instanceof String text ? text : null;
                // An out-of-range index from a modified client becomes a value no option can have, so the
                // validator reports UNKNOWN_OPTION exactly like the Java path with an unknown id.
                case Dropdown dropdown -> raw instanceof Integer index
                        ? java.util.Objects.requireNonNullElse(dropdown.idAt(index), "#" + index)
                        : null;
                case BedrockForm.Toggle ignored -> raw instanceof Boolean bool ? bool : null;
                case Slider ignored -> raw instanceof Number number ? number.floatValue() : null;
            };
            if (canonical != null) {
                values.put(field.key(), canonical);
            }
        }
        return new BedrockResponses(values);
    }

    /** The offered button chosen by the selector, or {@code null} when the index is not one of the buttons. */
    static @Nullable ButtonSpec action(Model model, Map<Integer, Object> submitted) {
        if (model.buttons().isEmpty()) {
            return null;
        }
        if (model.actionSelectorIndex() < 0) {
            return model.buttons().getFirst();
        }
        Object raw = submitted.get(model.actionSelectorIndex());
        if (raw instanceof Integer index && index >= 0 && index < model.buttons().size()) {
            return model.buttons().get(index);
        }
        return null;
    }

    /** The submitted values as text, only to pre-fill the form again after a rejection. */
    static Map<String, String> raw(Model model, Map<Integer, Object> submitted) {
        BedrockResponses responses = of(model, submitted);
        Map<String, String> raw = new LinkedHashMap<>();
        for (Field field : model.fields()) {
            Object value = responses.values.get(field.key());
            if (value == null) {
                continue;
            }
            raw.put(field.key(), switch (field) {
                case Slider ignored -> String.valueOf(((Number) value).floatValue());
                default -> String.valueOf(value);
            });
        }
        if (model.actionSelectorIndex() >= 0 && submitted.get(model.actionSelectorIndex()) instanceof Integer index) {
            raw.put(BedrockForm.ACTION_KEY, Integer.toString(index));
        }
        return raw;
    }

    @Override
    public @Nullable String text(String key) {
        return values.get(key) instanceof String text ? text : null;
    }

    @Override
    public @Nullable Boolean bool(String key) {
        return values.get(key) instanceof Boolean bool ? bool : null;
    }

    @Override
    public @Nullable Float number(String key) {
        return values.get(key) instanceof Float value ? value : null;
    }

    /** Keys that received a value; used by tests and to report what the client answered. */
    Set<String> keys() {
        return values.keySet();
    }
}
