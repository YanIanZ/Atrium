package dev.yanianz.atrium.module.dialogs;

import dev.yanianz.atrium.module.dialogs.BedrockForm.Dropdown;
import dev.yanianz.atrium.module.dialogs.BedrockForm.Element;
import dev.yanianz.atrium.module.dialogs.BedrockForm.Field;
import dev.yanianz.atrium.module.dialogs.BedrockForm.Label;
import dev.yanianz.atrium.module.dialogs.BedrockForm.Model;
import dev.yanianz.atrium.module.dialogs.BedrockForm.Slider;
import dev.yanianz.atrium.module.dialogs.BedrockForm.TextField;
import dev.yanianz.atrium.module.dialogs.BedrockForm.Toggle;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.ButtonSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.response.CustomFormResponse;
import org.geysermc.cumulus.response.SimpleFormResponse;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Floodgate/Geyser hook: shows a dialog to a Bedrock player as a native form instead of a Paper dialog, because a
 * Bedrock client cannot render one.
 *
 * <p>This is the only class that links against Floodgate, Cumulus and Geyser types. It is instantiated only after
 * {@code Bukkit.getPluginManager().isPluginEnabled("floodgate")} returned true, so a server without Floodgate never
 * loads these classes and cannot hit a {@link NoClassDefFoundError}. Every entry point catches {@link LinkageError}
 * and runtime failures of the API and reports "not available" to the caller, which then falls back to the Paper
 * dialog.
 *
 * <p>Form responses arrive on Geyser's thread. This class only reads the response (no world access) and hands the
 * values to {@link AnswerHandler}, which must move to the player's own thread itself.
 */
final class FloodgateDialogs {
    /** Called with the answered form; the caller hops to the player's thread before touching anything. */
    interface AnswerHandler {
        /**
         * @param clickedButton button index of a simple form, {@code -1} for a custom form (its selector decides)
         * @param values        component index to raw response value
         */
        void answer(Model model, int clickedButton, Map<Integer, Object> values);
    }

    /** Whether the viewer joined through Geyser. Never throws: an unavailable API means "not a Bedrock player". */
    boolean isBedrock(Player player) {
        try {
            return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    /**
     * Sends the form.
     *
     * @param actionLabel plain label of the synthetic button selector
     * @return {@code false} when the API refused the form or is not usable, so the caller can tell the player
     */
    boolean show(Player viewer, Model model, String actionLabel, AnswerHandler handler) {
        try {
            FloodgateApi api = FloodgateApi.getInstance();
            if (!api.isFloodgatePlayer(viewer.getUniqueId())) {
                return false;
            }
            return model.custom()
                    ? api.sendForm(viewer.getUniqueId(), custom(model, actionLabel, handler))
                    : api.sendForm(viewer.getUniqueId(), simple(model, handler));
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    private SimpleForm simple(Model model, AnswerHandler handler) {
        SimpleForm.Builder builder = SimpleForm.builder().title(model.title()).content(model.content());
        for (ButtonSpec button : model.buttons()) {
            builder.button(button.label());
        }
        return builder.validResultHandler(response -> answered(model, response, handler)).build();
    }

    private void answered(Model model, SimpleFormResponse response, AnswerHandler handler) {
        handler.answer(model, response.clickedButtonId(), Map.of());
    }

    private CustomForm custom(Model model, String actionLabel, AnswerHandler handler) {
        CustomForm.Builder builder = CustomForm.builder().title(model.title());
        for (Element element : model.elements()) {
            switch (element) {
                case Label label -> builder.label(label.text());
                case TextField text -> builder.input(text.label(), text.placeholder(), text.initial());
                case Dropdown dropdown -> builder.dropdown(dropdown.label(), dropdown.optionLabels(),
                        dropdown.initialIndex());
                case Toggle toggle -> builder.toggle(toggle.label(), toggle.initial());
                case Slider slider -> builder.slider(slider.label(), slider.start(), slider.end(), slider.step(),
                        slider.initial());
            }
        }
        if (model.actionSelectorIndex() >= 0) {
            List<String> labels = new ArrayList<>();
            for (ButtonSpec button : model.buttons()) {
                labels.add(button.label());
            }
            builder.dropdown(actionLabel, labels, model.actionSelectorInitial());
        }
        return builder.validResultHandler(response -> answered(model, response, handler)).build();
    }

    private void answered(Model model, CustomFormResponse response, AnswerHandler handler) {
        Map<Integer, Object> values = new HashMap<>();
        for (Field field : model.fields()) {
            values.put(field.index(), read(response, field));
        }
        if (model.actionSelectorIndex() >= 0) {
            values.put(model.actionSelectorIndex(), readDropdown(response, model.actionSelectorIndex()));
        }
        handler.answer(model, -1, values);
    }

    /**
     * One value of the response. A malformed payload (wrong type, missing entry) reads as {@code null} and is
     * rejected by {@link InputValidator} instead of throwing on Geyser's thread.
     */
    private static Object read(CustomFormResponse response, Field field) {
        try {
            return switch (field) {
                case TextField ignored -> response.asInput(field.index());
                case Dropdown ignored -> response.asDropdown(field.index());
                case Toggle ignored -> response.asToggle(field.index());
                case Slider ignored -> response.asSlider(field.index());
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Object readDropdown(CustomFormResponse response, int index) {
        try {
            return response.asDropdown(index);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
