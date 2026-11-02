package dev.yanianz.atrium.module.dialogs;

import dev.yanianz.atrium.module.dialogs.BedrockForm.Dropdown;
import dev.yanianz.atrium.module.dialogs.BedrockForm.Kind;
import dev.yanianz.atrium.module.dialogs.BedrockForm.Label;
import dev.yanianz.atrium.module.dialogs.BedrockForm.Model;
import dev.yanianz.atrium.module.dialogs.BedrockForm.Slider;
import dev.yanianz.atrium.module.dialogs.BedrockForm.TextField;
import dev.yanianz.atrium.module.dialogs.BedrockForm.Toggle;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.BodyLine;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.BoolInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.ButtonSpec;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.InputSpec;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.NumberInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.Option;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.OptionInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.TextInput;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The dialog -> Bedrock form mapping, including the component indexes a response is read from. */
class BedrockFormTest {
    static final Function<String, String> PLAIN = template -> template
            .replace("<gold>", "").replace("</gold>", "").replace("<player>", "Steve");
    static final Predicate<String> ALL = permission -> true;

    static final TextInput NAME = new TextInput("name", "<gold>Name</gold>", 200, true, "Steve", 16, 0, 0,
            Pattern.compile("[A-Za-z ]{0,16}"));
    static final OptionInput COLOUR = new OptionInput("colour", "Colour", 200, true, List.of(
            new Option("red", "<gold>Red</gold>", true),
            new Option("blue", "Blue", false)));
    static final BoolInput LOUD = new BoolInput("loud", "Loud", false, "yes", "no");
    static final NumberInput AMOUNT = new NumberInput("amount", "Amount", 200, 1, 10, 1f, 5f, null);

    static DialogDefinition definition(List<InputSpec> inputs, List<ButtonSpec> buttons, ButtonSpec exit,
            List<BodyLine> body) {
        return new DialogDefinition("test", "<gold>Title</gold>", "", "", List.of(), true, 2, body, inputs, buttons, exit,
                List.of(), null);
    }

    static ButtonSpec button(String label, String permission) {
        return new ButtonSpec(label, "", 150, permission, List.of("msg: hi"), null, List.of());
    }

    @Test void withoutInputsItIsASimpleFormWithButtons() {
        Model model = BedrockForm.build(definition(List.of(), List.of(button("<gold>Send</gold>", "")),
                button("Cancel", ""), List.of(new BodyLine("<gold>Hello</gold>", 200), new BodyLine("World", 200))),
                Map.of(), ALL, PLAIN);
        assertEquals(Kind.SIMPLE, model.kind());
        assertFalse(model.custom());
        assertEquals("Title", model.title());
        assertEquals("Hello\nWorld", model.content());
        assertEquals(List.of("Send", "Cancel"), model.buttons().stream().map(ButtonSpec::label).toList());
        assertEquals(-1, model.actionSelectorIndex());
        assertTrue(model.elements().isEmpty());
    }

    @Test void buttonsTheViewerMayNotUseAreNotShown() {
        Model model = BedrockForm.build(definition(List.of(), List.of(
                button("Everyone", ""), button("Vip", "atrium.vip"), button("Staff", "atrium.staff")),
                button("Exit", "atrium.exit"), List.of()),
                Map.of(), permission -> !permission.startsWith("atrium.vip") && !permission.startsWith("atrium.staff"),
                PLAIN);
        assertEquals(List.of("Everyone", "Exit"), model.buttons().stream().map(ButtonSpec::label).toList());
    }

    @Test void inputsBecomeIndexedComponentsWithLabelsCounted() {
        Model model = BedrockForm.build(definition(List.of(NAME, COLOUR, LOUD, AMOUNT),
                List.of(button("Send", "")), null, List.of(new BodyLine("Line", 200))),
                Map.of(), ALL, PLAIN);
        assertEquals(Kind.CUSTOM, model.kind());
        assertEquals(5, model.elements().size());
        assertInstanceOf(Label.class, model.elements().get(0));
        TextField text = (TextField) model.elements().get(1);
        assertEquals(1, text.index());
        assertEquals("name", text.key());
        assertEquals("Name", text.label());
        assertEquals("Steve", text.initial());
        Dropdown colour = (Dropdown) model.elements().get(2);
        assertEquals(2, colour.index());
        assertEquals(List.of("Red", "Blue"), colour.optionLabels());
        assertEquals(List.of("red", "blue"), colour.optionIds());
        assertEquals(0, colour.initialIndex(), "the option marked initial is preselected");
        Toggle toggle = (Toggle) model.elements().get(3);
        assertFalse(toggle.initial());
        Slider amount = (Slider) model.elements().get(4);
        assertEquals(1f, amount.start());
        assertEquals(10f, amount.end());
        assertEquals(1f, amount.step());
        assertEquals(5f, amount.initial());
        assertEquals(-1, model.actionSelectorIndex(), "a single button is the submit button");
        assertEquals(1, model.buttons().size());
    }

    @Test void severalButtonsGetADropdownAfterTheInputs() {
        Model model = BedrockForm.build(definition(List.of(NAME),
                List.of(button("Send", ""), button("Report", ""), button("Close", "")), null, List.of()),
                Map.of(), ALL, PLAIN);
        assertEquals(1, model.elements().size());
        assertEquals(1, model.actionSelectorIndex());
        assertEquals(3, model.buttons().size());
        assertEquals(0, model.actionSelectorInitial());
    }

    @Test void previousValuesPreFillAndKeepTheChosenButton() {
        Model model = BedrockForm.build(definition(List.of(NAME, COLOUR, LOUD, AMOUNT),
                        List.of(button("Send", ""), button("Other", "")), null, List.of()),
                Map.of("name", "Alex", "colour", "blue", "loud", "true", "amount", "8", "action-button", "1"),
                ALL, PLAIN);
        assertEquals("Alex", ((TextField) model.field("name")).initial());
        assertEquals(1, ((Dropdown) model.field("colour")).initialIndex());
        assertTrue(((Toggle) model.field("loud")).initial());
        assertEquals(8f, ((Slider) model.field("amount")).initial());
        assertEquals(1, model.actionSelectorInitial());
    }

    @Test void previousValuesThatAreNotUsableFallBackToTheConfiguredOnes() {
        Model model = BedrockForm.build(definition(List.of(NAME, COLOUR, AMOUNT), List.of(button("Send", "")),
                        null, List.of()),
                Map.of("name", "this name is far too long", "colour", "purple", "amount", "999"),
                ALL, PLAIN);
        assertEquals("Steve", ((TextField) model.field("name")).initial());
        assertEquals(0, ((Dropdown) model.field("colour")).initialIndex());
        assertEquals(5f, ((Slider) model.field("amount")).initial());
    }

    @Test void anUnusablePreviousOptionFallsBackToTheConfiguredInitialOption() {
        OptionInput colours = new OptionInput("colour", "Colour", 200, true, List.of(
                new Option("red", "Red", false), new Option("blue", "Blue", true)));
        Model model = BedrockForm.build(definition(List.of(colours), List.of(button("Send", "")), null, List.of()),
                Map.of("colour", "purple"), ALL, PLAIN);
        assertEquals(1, ((Dropdown) model.field("colour")).initialIndex());
    }

    @Test void continuousSlidersGetAPositiveStepInsideTheRange() {
        NumberInput continuous = new NumberInput("n", "N", 200, 0, 1, null, null, null);
        assertEquals(0.01f, BedrockForm.step(continuous), 1.0e-6);
        NumberInput coarse = new NumberInput("c", "C", 200, 0, 5, 50f, null, null);
        assertEquals(5f, BedrockForm.step(coarse), "a step wider than the range is clamped to the range");
    }

    @Test void customFormWithNoButtonsHasNoSelector() {
        Model model = BedrockForm.build(definition(List.of(NAME), List.of(), null, List.of()), Map.of(), ALL, PLAIN);
        assertEquals(-1, model.actionSelectorIndex());
        assertTrue(model.buttons().isEmpty());
    }
}
