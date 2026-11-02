package dev.yanianz.atrium.module.dialogs;

import dev.yanianz.atrium.core.util.Durations;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.BodyLine;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.ButtonSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the "preview" of a dialog shown by {@code /dialog preview}: the same layout, but every button is inert
 * and its label lists the actions it would run. Used for both rendering paths (Paper and Bedrock), so an admin
 * sees exactly what a player would see, minus the side effects. Pure.
 *
 * <p>The preview drops the dialog's own permission and cooldown (the command checks its own permission) and never
 * runs {@code on-open}; button permissions are kept so the preview shows which buttons a player would miss.
 */
final class DialogPreview {
    private static final int MAX_ACTION_LENGTH = 80;
    private static final int MAX_SUMMARY_LENGTH = 200;

    private DialogPreview() {}

    static DialogDefinition apply(DialogDefinition definition) {
        List<BodyLine> body = new ArrayList<>();
        body.add(new BodyLine("<dark_gray>Preview of <white>" + definition.id()
                + "</white>. Buttons show their actions and do nothing.</dark_gray>", 200));
        if (!definition.permission().isEmpty()) {
            body.add(new BodyLine("<dark_gray>Requires permission <white>" + definition.permission()
                    + "</white></dark_gray>", 200));
        }
        if (definition.cooldown() != null) {
            body.add(new BodyLine("<dark_gray>Cooldown: <white>"
                    + Durations.format(definition.cooldown(), 3)
                    + "</white></dark_gray>", 200));
        }
        if (!definition.onOpen().isEmpty()) {
            body.add(new BodyLine("<dark_gray>On open: <white>" + summary(definition.onOpen())
                    + "</white></dark_gray>", 200));
        }
        body.addAll(definition.body());
        List<ButtonSpec> buttons = new ArrayList<>();
        for (ButtonSpec button : definition.buttons()) {
            buttons.add(preview(button));
        }
        ButtonSpec exit = definition.exit() == null ? null : preview(definition.exit());
        return new DialogDefinition(definition.id(), "<dark_gray>[preview]</dark_gray> " + definition.title(),
                definition.externalTitle(), "", definition.canCloseWithEscape(), definition.columns(), body,
                definition.inputs(), buttons, exit, List.of(), null);
    }

    /** The button without its actions, with the actions spelled out in the label. */
    private static ButtonSpec preview(ButtonSpec button) {
        String described = describe(button);
        String label = described.isEmpty()
                ? button.label() + " <dark_gray>(no action)</dark_gray>"
                : button.label() + " <dark_gray>»</dark_gray> <gray>" + described + "</gray>";
        return new ButtonSpec(label, button.tooltip(), button.width(), button.permission(), List.of(), null);
    }

    private static String describe(ButtonSpec button) {
        List<String> parts = new ArrayList<>();
        if (!button.actions().isEmpty()) {
            parts.add(summary(button.actions()));
        }
        if (button.openDialog() != null) {
            parts.add("opens " + button.openDialog());
        }
        return String.join(" · ", parts);
    }

    private static String summary(List<String> lines) {
        List<String> parts = new ArrayList<>();
        for (String line : lines) {
            String text = line.length() > MAX_ACTION_LENGTH
                    ? line.substring(0, MAX_ACTION_LENGTH) + "…"
                    : line;
            parts.add(escape(text));
        }
        String joined = String.join(" · ", parts);
        return joined.length() > MAX_SUMMARY_LENGTH ? joined.substring(0, MAX_SUMMARY_LENGTH) + "…" : joined;
    }

    /** Keeps admin-written MiniMessage inside an action line from being parsed as part of the button label. */
    private static String escape(String text) {
        return text.replace("<", "\\<");
    }
}
