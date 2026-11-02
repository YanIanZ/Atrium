package dev.yanianz.atrium.module.dialogs;

import dev.yanianz.atrium.module.dialogs.BedrockForm.Model;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.BoolInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.ButtonSpec;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.InputSpec;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.NumberInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.Option;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.OptionInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.TextInput;
import dev.yanianz.atrium.module.dialogs.InputValidator.Accepted;
import dev.yanianz.atrium.module.dialogs.InputValidator.Reason;
import dev.yanianz.atrium.module.dialogs.InputValidator.Rejected;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation parity: the same answer must be accepted (or refused) identically whether it came from a Paper dialog
 * or from a Bedrock form, and a tampered Bedrock payload must not get further than a tampered Java one.
 */
class BedrockResponsesTest {
    static final Function<String, String> PLAIN = template -> template;
    static final TextInput NAME = new TextInput("name", "Name", 200, true, "Steve", 16, 0, 0,
            Pattern.compile("[A-Za-z ]{0,16}"));
    static final OptionInput COLOUR = new OptionInput("colour", "Colour", 200, true,
            List.of(new Option("red", "Red", true), new Option("blue", "Blue", false)));
    static final BoolInput LOUD = new BoolInput("loud", "Loud", false, "yes", "no");
    static final NumberInput AMOUNT = new NumberInput("amount", "Amount", 200, 1, 64, 1f, null, null);
    static final List<InputSpec> INPUTS = List.of(NAME, COLOUR, LOUD, AMOUNT);

    static final Model MODEL = BedrockForm.build(new DialogDefinition("x", "T", "", "", true, 2, List.of(),
                    INPUTS, List.of(new ButtonSpec("Send", "", 150, "", List.of("msg: hi"), null),
                            new ButtonSpec("Other", "", 150, "", List.of("msg: hi"), null)),
                    null, List.of(), null),
            Map.of(), permission -> true, PLAIN);

    /** The Java path: text values, option ids, booleans and floats. */
    static final class Java implements InputValidator.ResponseValues {
        final Map<String, Object> values = new HashMap<>();

        Java with(String key, Object value) {
            values.put(key, value);
            return this;
        }

        @Override public String text(String key) {
            return values.get(key) instanceof String text ? text : null;
        }

        @Override public Boolean bool(String key) {
            return values.get(key) instanceof Boolean bool ? bool : null;
        }

        @Override public Float number(String key) {
            return values.get(key) instanceof Float number ? number : null;
        }
    }

    /** Raw Bedrock payload: component index to what the client sent. */
    static Map<Integer, Object> answer(Object name, Object colourIndex, Object loud, Object amount) {
        Map<Integer, Object> submitted = new HashMap<>();
        if (name != null) {
            submitted.put(0, name);
        }
        if (colourIndex != null) {
            submitted.put(1, colourIndex);
        }
        if (loud != null) {
            submitted.put(2, loud);
        }
        if (amount != null) {
            submitted.put(3, amount);
        }
        return submitted;
    }

    @Test void indexesMatchTheCustomFormLayout() {
        assertEquals(4, MODEL.fields().size());
        assertEquals(0, MODEL.fields().get(0).index());
        assertEquals(3, MODEL.fields().get(3).index());
    }

    @Test void sameAnswerGivesTheSameVariables() {
        InputValidator.Result java = InputValidator.validate(INPUTS,
                new Java().with("name", "Alex").with("colour", "blue").with("loud", true).with("amount", 12.6f));
        InputValidator.Result bedrock = InputValidator.validate(INPUTS,
                BedrockResponses.of(MODEL, answer("Alex", 1, true, 12.6f)));
        assertEquals(Accepted.class, java.getClass());
        assertEquals(((Accepted) java).variables(), ((Accepted) bedrock).variables());
        assertEquals("Alex", ((Accepted) java).variables().get("name"));
        assertEquals("blue", ((Accepted) java).variables().get("colour"));
        assertEquals("yes", ((Accepted) java).variables().get("loud"));
        assertEquals("13", ((Accepted) java).variables().get("amount"));
    }

    @Test void sameBadAnswerIsRejectedForTheSameReason() {
        // Text with characters the admin pattern forbids.
        assertRejectedTheSame(Reason.PATTERN,
                new Java().with("name", "x; op me").with("colour", "red").with("loud", false).with("amount", 1f),
                answer("x; op me", 0, false, 1f));
        // An option id that was never offered versus a dropdown index that was never offered.
        assertRejectedTheSame(Reason.UNKNOWN_OPTION,
                new Java().with("name", "Alex").with("colour", "purple").with("loud", false).with("amount", 1f),
                answer("Alex", 7, false, 1f));
        // A slider value outside the range.
        assertRejectedTheSame(Reason.OUT_OF_RANGE,
                new Java().with("name", "Alex").with("colour", "red").with("loud", false).with("amount", -1000f),
                answer("Alex", 0, false, -1000f));
        // Too long, measured on the server.
        assertRejectedTheSame(Reason.TOO_LONG,
                new Java().with("name", "a".repeat(20)).with("colour", "red").with("loud", false).with("amount", 1f),
                answer("a".repeat(20), 0, false, 1f));
    }

    static void assertRejectedTheSame(Reason reason, Java java, Map<Integer, Object> bedrock) {
        InputValidator.Result javaResult = InputValidator.validate(INPUTS, java);
        InputValidator.Result bedrockResult = InputValidator.validate(INPUTS, BedrockResponses.of(MODEL, bedrock));
        assertInstanceOf(Rejected.class, javaResult);
        assertInstanceOf(Rejected.class, bedrockResult);
        assertEquals(reason, ((Rejected) javaResult).reason());
        assertEquals(reason, ((Rejected) bedrockResult).reason());
        assertEquals(((Rejected) javaResult).input().key(), ((Rejected) bedrockResult).input().key());
    }

    @Test void aPayloadOfTheWrongTypeReadsAsMissingInsteadOfThrowing() {
        InputValidator.Result result = InputValidator.validate(INPUTS, BedrockResponses.of(MODEL,
                answer(1234, "blue", "yes", "lots")));
        assertEquals(Reason.MISSING, ((Rejected) result).reason());
        assertEquals("name", ((Rejected) result).input().key());
    }

    @Test void missingEntriesReadAsMissing() {
        assertEquals(Reason.MISSING,
                ((Rejected) InputValidator.validate(INPUTS, BedrockResponses.of(MODEL, Map.of()))).reason());
    }

    @Test void sliderRangeAndSnappingUseTheSameValidator() {
        assertEquals("64", ((Accepted) InputValidator.validate(List.of(AMOUNT),
                BedrockResponses.of(MODEL, answer(null, null, null, 64.00001f)))).variables().get("amount"));
        assertEquals(Reason.OUT_OF_RANGE,
                ((Rejected) InputValidator.validate(List.of(AMOUNT),
                        BedrockResponses.of(MODEL, answer(null, null, null, 65f)))).reason());
    }

    @Test void theSelectorPicksTheButtonAndRefusesAnythingElse() {
        Map<Integer, Object> submitted = new HashMap<>();
        submitted.put(MODEL.actionSelectorIndex(), 1);
        assertEquals("Other", BedrockResponses.action(MODEL, submitted).label());
        submitted.put(MODEL.actionSelectorIndex(), 9);
        assertNull(BedrockResponses.action(MODEL, submitted));
        submitted.put(MODEL.actionSelectorIndex(), "one");
        assertNull(BedrockResponses.action(MODEL, submitted));
        assertNull(BedrockResponses.action(MODEL, Map.of()));
    }

    @Test void rawValuesCanPreFillTheFormAgain() {
        Map<String, String> raw = BedrockResponses.raw(MODEL,
                answer("Alex", 1, true, 12.6f));
        assertEquals("Alex", raw.get("name"));
        assertEquals("blue", raw.get("colour"));
        assertEquals("true", raw.get("loud"));
        assertEquals("12.6", raw.get("amount"));
    }
}
