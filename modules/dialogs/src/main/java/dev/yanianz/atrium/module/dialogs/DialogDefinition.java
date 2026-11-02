package dev.yanianz.atrium.module.dialogs;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One admin-defined dialog, parsed from {@code plugins/Atrium/modules/dialogs/<id>.yml}. Immutable and free of
 * Paper types so it can be unit tested; {@link DialogRenderer} turns it into a Paper dialog per viewer and
 * {@link BedrockForm} into the Bedrock (Floodgate) equivalent.
 *
 * <p>Text fields ({@code title}, labels, body lines, tooltips) are trusted MiniMessage templates written by the
 * admin. Values typed by players never end up in these templates.
 *
 * <p>{@code extends} is resolved before parsing ({@link DialogInheritance}); this record always describes a
 * complete, self-contained dialog.
 *
 * @param permission empty when anyone with {@code atrium.dialog.open} may open it
 * @param conditions extra gates evaluated on the viewer (permission and placeholder comparisons)
 * @param exit       button used when the dialog is closed with escape; {@code null} for none
 * @param onOpen     admin-written action lines run once when the dialog is opened by a player
 * @param cooldown   minimum time between two opens of this dialog by the same player; {@code null} for none
 */
record DialogDefinition(
        String id,
        String title,
        String externalTitle,
        String permission,
        List<Condition> conditions,
        boolean canCloseWithEscape,
        int columns,
        List<BodyLine> body,
        List<InputSpec> inputs,
        List<ButtonSpec> buttons,
        @Nullable ButtonSpec exit,
        List<String> onOpen,
        @Nullable Duration cooldown) {

    DialogDefinition {
        Objects.requireNonNull(id, "id");
        body = List.copyOf(body);
        conditions = List.copyOf(conditions);
        inputs = List.copyOf(inputs);
        buttons = List.copyOf(buttons);
        onOpen = List.copyOf(onOpen);
    }

    /** The same dialog with a different button list (used to hide buttons the viewer may not use). */
    DialogDefinition withButtons(List<ButtonSpec> buttons, @Nullable ButtonSpec exit) {
        return new DialogDefinition(id, title, externalTitle, permission, conditions, canCloseWithEscape, columns,
                body, inputs, buttons, exit, onOpen, cooldown);
    }

    Optional<InputSpec> input(String key) {
        return inputs.stream().filter(input -> input.key().equals(key)).findFirst();
    }

    /** A paragraph of body text. */
    record BodyLine(String text, int width) {}

    /** A clickable button. Actions are admin-written {@code ActionService} lines. */
    record ButtonSpec(String label, String tooltip, int width, String permission, List<String> actions,
            @Nullable String openDialog, List<Condition> conditions) {
        ButtonSpec {
            actions = List.copyOf(actions);
            conditions = List.copyOf(conditions);
        }
    }

    /** One input field. {@link #key()} is the variable name in actions ({@code {key}}). */
    sealed interface InputSpec permits TextInput, OptionInput, BoolInput, NumberInput {
        String key();

        String label();
    }

    /**
     * Free text. The submitted value must be at most {@code maxLength} characters and fully match {@code pattern};
     * otherwise the click is rejected before any action runs.
     *
     * @param multilineLines 0 for a single-line field
     */
    record TextInput(String key, String label, int width, boolean labelVisible, String initial, int maxLength,
            int multilineLines, int multilineHeight, Pattern pattern) implements InputSpec {}

    /** One choice out of a fixed list. The submitted id must be one of {@link #options()}. */
    record OptionInput(String key, String label, int width, boolean labelVisible, List<Option> options)
            implements InputSpec {
        OptionInput {
            options = List.copyOf(options);
        }

        boolean hasOption(String id) {
            return options.stream().anyMatch(option -> option.id().equals(id));
        }
    }

    record Option(String id, String label, boolean initial) {}

    /** A checkbox; the variable becomes {@code onTrue} or {@code onFalse}. */
    record BoolInput(String key, String label, boolean initial, String onTrue, String onFalse) implements InputSpec {}

    /**
     * A slider. The submitted number must be finite and inside {@code [start, end]}; it is snapped to {@code step}.
     *
     * @param step    {@code null} for a continuous slider
     * @param initial {@code null} for the middle of the range
     */
    record NumberInput(String key, String label, int width, float start, float end, @Nullable Float step,
            @Nullable Float initial, @Nullable String labelFormat) implements InputSpec {}
}
