package dev.yanianz.atrium.module.dialogs;

import dev.yanianz.atrium.module.dialogs.DialogDefinition.BodyLine;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.BoolInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.ButtonSpec;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.InputSpec;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.NumberInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.Option;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.OptionInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.TextInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The Bedrock (Floodgate) shape of a dialog: either a {@code SimpleForm} (no inputs) or a {@code CustomForm}
 * (at least one input). Pure, with no Floodgate or Cumulus types, so the mapping from {@link DialogDefinition} is
 * unit tested; {@link FloodgateDialogs} only turns this description into the real form objects.
 *
 * <p>Bedrock forms are answered by position, not by key: {@link #elements()} is the exact component order,
 * labels included, and {@link Field#index()} is the absolute index the response value must be read from. That
 * mirrors Cumulus' response array, which stores {@code null} for a label.
 *
 * <p>Because a custom form has a single submit button, a dialog with more than one button gets an extra dropdown
 * (at {@link Model#actionSelectorIndex()}) that picks the button. Its key is {@link #ACTION_KEY}, which is not a
 * legal input key, so it can never collide with an input.
 */
final class BedrockForm {
    /** Key of the synthetic button-picker dropdown. Not a valid input key ({@code -} is not allowed). */
    static final String ACTION_KEY = "action-button";
    /** Steps used for a continuous slider so Bedrock gets a positive step. */
    private static final int CONTINUOUS_STEPS = 100;

    enum Kind {
        SIMPLE,
        CUSTOM
    }

    sealed interface Element permits Label, Field {}

    /** Body text: shown as content in a simple form, as a label in a custom form. */
    record Label(String text) implements Element {}

    /** One value the client returns. */
    sealed interface Field extends Element permits TextField, Dropdown, Toggle, Slider {
        /** Absolute index in the custom form, labels included. */
        int index();

        String key();
    }

    record TextField(int index, String key, String label, String placeholder, String initial) implements Field {}

    /** A dropdown answers with the index of the chosen entry, never with the id. */
    record Dropdown(int index, String key, String label, List<String> optionLabels, List<String> optionIds,
            int initialIndex) implements Field {
        Dropdown {
            optionLabels = List.copyOf(optionLabels);
            optionIds = List.copyOf(optionIds);
        }

        /** The option id behind a submitted index, or {@code null} when the client sent something else. */
        @Nullable String idAt(@Nullable Integer selected) {
            return selected != null && selected >= 0 && selected < optionIds.size()
                    ? optionIds.get(selected)
                    : null;
        }
    }

    record Toggle(int index, String key, String label, boolean initial) implements Field {}

    record Slider(int index, String key, String label, float start, float end, float step, float initial,
            @Nullable String labelFormat) implements Field {}

    /**
     * @param buttons            actions shown as buttons (simple form) or offered by the selector (custom form);
     *                           labels are plain text, tooltips are dropped (Bedrock has none)
     * @param actionSelectorIndex index of the selector dropdown in {@link #elements()}, {@code -1} for none
     * @param actionSelectorInitial preselected entry of that dropdown
     */
    record Model(Kind kind, String title, String content, List<Element> elements, List<ButtonSpec> buttons,
            int actionSelectorIndex, int actionSelectorInitial) {
        Model {
            elements = List.copyOf(elements);
            buttons = List.copyOf(buttons);
        }

        boolean custom() {
            return kind == Kind.CUSTOM;
        }

        List<Field> fields() {
            List<Field> fields = new ArrayList<>();
            for (Element element : elements) {
                if (element instanceof Field field) {
                    fields.add(field);
                }
            }
            return fields;
        }

        @Nullable Field field(String key) {
            for (Element element : elements) {
                if (element instanceof Field field && field.key().equals(key)) {
                    return field;
                }
            }
            return null;
        }
    }

    private BedrockForm() {}

    /**
     * @param previous raw values submitted earlier, used to pre-fill the form again after a rejected submission
     * @param allowed  permission check for buttons and the exit button
     * @param plain    turns one trusted MiniMessage template into plain text (Bedrock does not render MiniMessage)
     */
    static Model build(DialogDefinition definition, Map<String, String> previous, Predicate<String> allowed,
            Function<String, String> plain) {
        String title = plain.apply(definition.title());
        List<ButtonSpec> visible = new ArrayList<>();
        for (ButtonSpec button : definition.buttons()) {
            if (allowed.test(button.permission())) {
                visible.add(plain(button, plain));
            }
        }
        if (definition.inputs().isEmpty()) {
            if (definition.exit() != null && allowed.test(definition.exit().permission())) {
                visible.add(plain(definition.exit(), plain));
            }
            List<String> content = new ArrayList<>();
            for (BodyLine line : definition.body()) {
                content.add(plain.apply(line.text()));
            }
            return new Model(Kind.SIMPLE, title, String.join("\n", content), List.of(), visible, -1, 0);
        }
        List<Element> elements = new ArrayList<>();
        for (BodyLine line : definition.body()) {
            elements.add(new Label(plain.apply(line.text())));
        }
        for (InputSpec input : definition.inputs()) {
            int index = elements.size();
            elements.add(field(input, index, previous.get(input.key()), plain));
        }
        int selector = visible.size() > 1 ? elements.size() : -1;
        int selectorInitial = previousIndex(previous.get(ACTION_KEY), visible.size());
        return new Model(Kind.CUSTOM, title, "", elements, visible, selector, selectorInitial);
    }

    /** The entry of the button selector to preselect, from an earlier submission. */
    private static int previousIndex(@Nullable String previous, int size) {
        if (previous == null) {
            return 0;
        }
        try {
            int index = Integer.parseInt(previous.trim());
            return index >= 0 && index < size ? index : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Field field(InputSpec input, int index, @Nullable String previous,
            Function<String, String> plain) {
        return switch (input) {
            case TextInput text -> new TextField(index, text.key(), plain.apply(text.label()),
                    "", initialText(text, previous));
            case OptionInput option -> dropdown(index, option, previous, plain);
            case BoolInput bool -> new Toggle(index, bool.key(), plain.apply(bool.label()),
                    previous != null ? Boolean.parseBoolean(previous) : bool.initial());
            case NumberInput number -> {
                float step = step(number);
                float middle = number.start() + ((number.end() - number.start()) / 2.0f);
                float initial = number.initial() != null ? number.initial() : middle;
                if (previous != null) {
                    try {
                        float value = Float.parseFloat(previous);
                        if (Float.isFinite(value) && value >= number.start() && value <= number.end()) {
                            initial = value;
                        }
                    } catch (NumberFormatException ignored) {
                        // Not a number: keep the configured initial value.
                    }
                }
                yield new Slider(index, number.key(), plain.apply(number.label()), number.start(), number.end(),
                        step, initial, number.labelFormat());
            }
        };
    }

    private static Dropdown dropdown(int index, OptionInput option, @Nullable String previous,
            Function<String, String> plain) {
        List<String> labels = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        int configured = 0;
        int matched = -1;
        for (Option entry : option.options()) {
            if (entry.initial() && matched < 0) {
                configured = ids.size();
            }
            if (previous != null && entry.id().equals(previous)) {
                matched = ids.size();
            }
            labels.add(plain.apply(entry.label()));
            ids.add(entry.id());
        }
        return new Dropdown(index, option.key(), plain.apply(option.label()), labels, ids,
                matched >= 0 ? matched : configured);
    }

    private static String initialText(TextInput text, @Nullable String previous) {
        return previous != null && previous.length() <= text.maxLength() ? previous : text.initial();
    }

    /** The same button with a plain label; actions, permission and the opened dialog are kept. */
    private static ButtonSpec plain(ButtonSpec button, Function<String, String> plain) {
        return new ButtonSpec(plain.apply(button.label()), "", button.width(), button.permission(), button.actions(),
                button.openDialog(), button.conditions());
    }

    /** A positive step for the client; a continuous slider becomes 100 reasonable steps. */
    static float step(NumberInput number) {
        float range = number.end() - number.start();
        float step = number.step() != null ? number.step() : range / CONTINUOUS_STEPS;
        if (!Float.isFinite(step) || step <= 0) {
            return 1;
        }
        return Math.min(step, range);
    }
}
