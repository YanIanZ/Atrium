package dev.yanianz.atrium.module.dialogs;

import dev.yanianz.atrium.module.dialogs.DialogDefinition.BoolInput;
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
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputValidatorTest {
    static final Pattern DEFAULT = Pattern.compile("[A-Za-z0-9_ -]{0,32}");

    static TextInput text(String key, int max, Pattern pattern) {
        return new TextInput(key, key, 200, true, "", max, 0, 0, pattern);
    }

    static final OptionInput COLOUR = new OptionInput("colour", "Colour", 200, true,
            List.of(new Option("red", "Red", true), new Option("blue", "Blue", false)));
    static final NumberInput AMOUNT = new NumberInput("amount", "Amount", 200, 1, 64, 1f, null, null);
    static final BoolInput LOUD = new BoolInput("loud", "Loud", false, "yes", "no");

    /** A fake client response. */
    static final class Response implements InputValidator.ResponseValues {
        final Map<String, Object> values = new HashMap<>();

        Response with(String key, Object value) {
            values.put(key, value);
            return this;
        }

        @Override public String text(String key) {
            return values.get(key) instanceof String s ? s : null;
        }

        @Override public Boolean bool(String key) {
            return values.get(key) instanceof Boolean b ? b : null;
        }

        @Override public Float number(String key) {
            return values.get(key) instanceof Float f ? f : null;
        }
    }

    static InputValidator.Result validate(List<InputSpec> inputs, Response response) {
        return InputValidator.validate(inputs, response);
    }

    @Test void acceptsValidValuesLiterally() {
        var result = validate(List.of(text("name", 32, DEFAULT), COLOUR, AMOUNT, LOUD), new Response()
                .with("name", "Steve the 2nd").with("colour", "blue").with("amount", 12f).with("loud", true));
        Map<String, String> vars = ((Accepted) result).variables();
        assertEquals("Steve the 2nd", vars.get("name"));
        assertEquals("blue", vars.get("colour"));
        assertEquals("12", vars.get("amount"));
        assertEquals("yes", vars.get("loud"));
    }

    @Test void rejectsInjectionAttemptsWithTheDefaultPattern() {
        TextInput name = text("name", 64, DEFAULT);
        for (String evil : List.of("%player_ip%", "x; op me", "<click:run_command:/op me>hi", "{player}",
                "a\nb", "§cred", "@a", "\"quoted\"", "a/b", "${jndi:x}", "a&b")) {
            var result = validate(List.of(name), new Response().with("name", evil));
            assertInstanceOf(Rejected.class, result, evil);
            assertEquals(Reason.PATTERN, ((Rejected) result).reason(), evil);
        }
    }

    @Test void enforcesMaxLengthOnTheServer() {
        var result = validate(List.of(text("name", 5, Pattern.compile(".*"))), new Response().with("name", "123456"));
        assertEquals(Reason.TOO_LONG, ((Rejected) result).reason());
        assertInstanceOf(Accepted.class,
                validate(List.of(text("name", 5, Pattern.compile(".*"))), new Response().with("name", "12345")));
    }

    @Test void adminPatternIsAFullMatch() {
        TextInput player = text("target", 16, Pattern.compile("[A-Za-z0-9_]{3,16}"));
        assertInstanceOf(Accepted.class, validate(List.of(player), new Response().with("target", "Notch_1")));
        assertInstanceOf(Rejected.class, validate(List.of(player), new Response().with("target", "Notch extra")));
        assertInstanceOf(Rejected.class, validate(List.of(player), new Response().with("target", "ab")));
    }

    @Test void rejectsOptionIdsThatWereNotOffered() {
        for (String id : List.of("green", "RED", "red ", "", "red\u0000")) {
            var result = validate(List.of(COLOUR), new Response().with("colour", id));
            assertEquals(Reason.UNKNOWN_OPTION, ((Rejected) result).reason(), id);
        }
    }

    @Test void missingValuesRejectInsteadOfThrowing() {
        for (InputSpec input : List.<InputSpec>of(text("name", 8, DEFAULT), COLOUR, AMOUNT, LOUD)) {
            var result = validate(List.of(input), new Response());
            assertEquals(Reason.MISSING, ((Rejected) result).reason(), input.key());
        }
        // Wrong type for the key reads as missing too.
        assertEquals(Reason.MISSING,
                ((Rejected) validate(List.of(AMOUNT), new Response().with("amount", "5"))).reason());
    }

    @Test void numbersAreRangeCheckedSnappedAndFormattedLocaleIndependently() {
        assertEquals(Reason.OUT_OF_RANGE,
                ((Rejected) validate(List.of(AMOUNT), new Response().with("amount", -1000000f))).reason());
        assertEquals(Reason.OUT_OF_RANGE,
                ((Rejected) validate(List.of(AMOUNT), new Response().with("amount", 65f))).reason());
        assertEquals(Reason.OUT_OF_RANGE,
                ((Rejected) validate(List.of(AMOUNT), new Response().with("amount", Float.NaN))).reason());
        assertEquals(Reason.OUT_OF_RANGE,
                ((Rejected) validate(List.of(AMOUNT), new Response().with("amount", Float.POSITIVE_INFINITY)))
                        .reason());
        assertEquals("13", InputValidator.number(AMOUNT, 12.6f));
        assertEquals("64", InputValidator.number(AMOUNT, 64.00001f));
        NumberInput half = new NumberInput("h", "h", 200, 0, 1, 0.25f, null, null);
        assertEquals("0.5", InputValidator.number(half, 0.49f));
        assertEquals("0", InputValidator.number(half, 0.01f));
        NumberInput free = new NumberInput("f", "f", 200, -1, 1, null, null, null);
        assertEquals("0.3333", InputValidator.number(free, 1f / 3f));
        assertNull(InputValidator.number(free, 1.0e10f));
    }

    @Test void oneBadValueRejectsTheWholeResponse() {
        var result = validate(List.of(text("a", 8, DEFAULT), COLOUR), new Response()
                .with("a", "fine").with("colour", "purple"));
        Rejected rejected = (Rejected) result;
        assertEquals("colour", rejected.input().key());
    }
}
