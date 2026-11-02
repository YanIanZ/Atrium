package dev.yanianz.atrium.module.dialogs;

import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.BodyLine;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.BoolInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.ButtonSpec;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.InputSpec;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.NumberInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.Option;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.OptionInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.TextInput;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.type.MultiActionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Builds the Paper dialog for one viewer. Every button gets its own one-use custom-click callback (no command
 * template is ever sent to the client, so player input never becomes part of a command line the client builds).
 * Templates are admin-written MiniMessage; the only placeholder is {@code <player>}, the viewer's name, inserted as
 * plain text. Call on the viewer's thread.
 */
final class DialogRenderer {
    /** Receives a click; called on whatever thread Paper delivers it on. */
    interface ClickHandler {
        void click(ButtonSpec button, DialogResponseView response, Audience audience);
    }

    private final Messages messages;

    DialogRenderer(Messages messages) {
        this.messages = messages;
    }

    /**
     * @param previous raw values submitted earlier (re-shown after a rejected submission); only values that are
     *                 valid initial values for their input are used
     */
    Dialog render(DialogDefinition definition, Player viewer, Map<String, String> previous, ClickHandler handler,
            ClickCallback.Options options, java.util.function.Predicate<java.util.List<Condition>> conditionsMet) {
        List<DialogBody> body = new ArrayList<>();
        for (BodyLine line : definition.body()) {
            body.add(DialogBody.plainMessage(text(line.text(), viewer), line.width()));
        }
        List<DialogInput> inputs = new ArrayList<>();
        for (InputSpec input : definition.inputs()) {
            inputs.add(input(input, viewer, previous.get(input.key())));
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (ButtonSpec button : definition.buttons()) {
            if (allowed(viewer, button.permission()) && conditionsMet.test(button.conditions())) {
                buttons.add(button(button, viewer, handler, options));
            }
        }
        ActionButton exit = definition.exit() != null && allowed(viewer, definition.exit().permission())
                ? button(definition.exit(), viewer, handler, options)
                : null;

        DialogBase.Builder base = DialogBase.builder(text(definition.title(), viewer))
                .canCloseWithEscape(definition.canCloseWithEscape())
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(body)
                .inputs(inputs);
        if (!definition.externalTitle().isEmpty()) {
            base.externalTitle(text(definition.externalTitle(), viewer));
        }
        DialogType type;
        if (buttons.isEmpty()) {
            type = exit == null ? DialogType.notice() : DialogType.notice(exit);
        } else {
            MultiActionType.Builder multi = DialogType.multiAction(buttons)
                    .columns(Math.max(1, Math.min(definition.columns(), buttons.size())));
            if (exit != null) {
                multi.exitAction(exit);
            }
            type = multi.build();
        }
        DialogBase built = base.build();
        return Dialog.create(factory -> factory.empty().base(built).type(type));
    }

    static boolean allowed(Player viewer, String permission) {
        return permission.isEmpty() || viewer.hasPermission(permission);
    }

    /** The same dialog with only the buttons the viewer may use (permission and conditions). */
    static DialogDefinition visible(DialogDefinition definition, Player viewer,
            java.util.function.Predicate<java.util.List<Condition>> conditionsMet) {
        java.util.List<ButtonSpec> buttons = new java.util.ArrayList<>();
        for (ButtonSpec button : definition.buttons()) {
            if (allowed(viewer, button.permission()) && conditionsMet.test(button.conditions())) {
                buttons.add(button);
            }
        }
        ButtonSpec exit = definition.exit() != null && allowed(viewer, definition.exit().permission())
                ? definition.exit() : null;
        return definition.withButtons(buttons, exit);
    }

    Component text(String template, Player viewer) {
        return messages.parseTrusted(template, Msg.unparsed("player", viewer.getName()));
    }

    private ActionButton button(ButtonSpec spec, Player viewer, ClickHandler handler, ClickCallback.Options options) {
        ActionButton.Builder builder = ActionButton.builder(text(spec.label(), viewer)).width(spec.width());
        if (!spec.tooltip().isEmpty()) {
            builder.tooltip(text(spec.tooltip(), viewer));
        }
        // Every button carries a callback, also one without actions (a preview button): the click is then read
        // and validated on the player's thread, and the dialog closes through afterAction either way.
        builder.action(DialogAction.customClick(
                (response, audience) -> handler.click(spec, response, audience), options));
        return builder.build();
    }

    private DialogInput input(InputSpec spec, Player viewer, @Nullable String previous) {
        Component label = text(spec.label(), viewer);
        return switch (spec) {
            case TextInput text -> {
                String initial = previous != null && previous.length() <= text.maxLength() ? previous : text.initial();
                TextDialogInput.Builder builder = DialogInput.text(text.key(), label)
                        .width(text.width())
                        .labelVisible(text.labelVisible())
                        .initial(initial)
                        .maxLength(text.maxLength());
                if (text.multilineLines() > 0) {
                    builder.multiline(TextDialogInput.MultilineOptions.create(text.multilineLines(),
                            text.multilineHeight() > 0 ? text.multilineHeight() : null));
                }
                yield builder.build();
            }
            case OptionInput option -> {
                String selected = previous != null && option.hasOption(previous) ? previous : null;
                List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
                for (Option entry : option.options()) {
                    boolean initial = selected != null ? entry.id().equals(selected) : entry.initial();
                    entries.add(SingleOptionDialogInput.OptionEntry.create(entry.id(), text(entry.label(), viewer),
                            initial));
                }
                yield DialogInput.singleOption(option.key(), label, entries)
                        .width(option.width())
                        .labelVisible(option.labelVisible())
                        .build();
            }
            case BoolInput bool -> {
                boolean initial = previous != null ? Boolean.parseBoolean(previous) : bool.initial();
                yield DialogInput.bool(bool.key(), label).initial(initial).build();
            }
            case NumberInput number -> {
                Float initial = number.initial();
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
                var builder = DialogInput.numberRange(number.key(), label, number.start(), number.end())
                        .width(number.width())
                        .step(number.step())
                        .initial(initial);
                if (number.labelFormat() != null) {
                    builder.labelFormat(number.labelFormat());
                }
                yield builder.build();
            }
        };
    }
}
