package dev.yanianz.atrium.module.dialogs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Rules of {@code extends}, without touching the disk: the naive merge is where shared headers go wrong. */
class DialogInheritanceTest {

    static Map<String, Object> tree(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    static Map<String, Map<String, Object>> files(Object... idTreePairs) {
        Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        for (int i = 0; i < idTreePairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            Map<String, Object> tree = (Map<String, Object>) idTreePairs[i + 1];
            files.put((String) idTreePairs[i], tree);
        }
        return files;
    }

    @Test void childKeepsItsOwnScalarsAndInheritsTheRest() throws Exception {
        Map<String, Object> resolved = DialogInheritance.resolve("child", files(
                "base", tree("title", "Base", "columns", 3, "permission", "atrium.base"),
                "child", tree("extends", "base", "title", "Child")));
        assertEquals("Child", resolved.get("title"));
        assertEquals(3, resolved.get("columns"));
        assertEquals("atrium.base", resolved.get("permission"));
        assertFalse(resolved.containsKey("extends"), "extends is resolved, never parsed twice");
    }

    @Test void listsAreAppendedParentFirst() throws Exception {
        Map<String, Object> resolved = DialogInheritance.resolve("child", files(
                "base", tree("title", "B", "body", List.of("header"), "buttons", List.of(tree("label", "Shared")),
                        "on-open", List.of("msg: hi")),
                "child", tree("extends", "base", "body", List.of("own"),
                        "buttons", List.of(tree("label", "Own")), "on-open", List.of("msg: child"))));
        assertEquals(List.of("header", "own"), resolved.get("body"));
        assertEquals(List.of(tree("label", "Shared"), tree("label", "Own")), resolved.get("buttons"));
        assertEquals(List.of("msg: hi", "msg: child"), resolved.get("on-open"));
    }

    @Test void aScalarOrListBodyIsConcatenatedToo() throws Exception {
        Map<String, Object> resolved = DialogInheritance.resolve("child", files(
                "base", tree("title", "B", "body", "one line"),
                "child", tree("extends", "base", "body", List.of("two"))));
        assertEquals(List.of("one line", "two"), resolved.get("body"));
    }

    @Test void childInputReplacesTheParentEntryWithTheSameKeyInPlace() throws Exception {
        Map<String, Object> resolved = DialogInheritance.resolve("child", files(
                "base", tree("title", "B", "inputs", List.of(
                        tree("key", "name", "max-length", 8),
                        tree("key", "reason", "type", "text"))),
                "child", tree("extends", "base", "inputs", List.of(
                        tree("key", "name", "max-length", 16),
                        tree("key", "extra", "type", "bool")))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inputs = (List<Map<String, Object>>) resolved.get("inputs");
        assertEquals(3, inputs.size());
        assertEquals("name", inputs.get(0).get("key"));
        assertEquals(16, inputs.get(0).get("max-length"));
        assertEquals("reason", inputs.get(1).get("key"));
        assertEquals("extra", inputs.get(2).get("key"));
    }

    @Test void inheritsThroughSeveralLevels() throws Exception {
        Map<String, Object> resolved = DialogInheritance.resolve("grandchild", files(
                "root", tree("title", "Root", "body", List.of("root")),
                "middle", tree("extends", "root", "body", List.of("middle")),
                "grandchild", tree("extends", "middle", "body", List.of("leaf"), "columns", 4)));
        assertEquals("Root", resolved.get("title"));
        assertEquals(4, resolved.get("columns"));
        assertEquals(List.of("root", "middle", "leaf"), resolved.get("body"));
    }

    @Test void refusesACycle() {
        DialogSyntaxException e = assertThrows(DialogSyntaxException.class, () -> DialogInheritance.resolve("a",
                files("a", tree("extends", "b"), "b", tree("extends", "a"))));
        assertTrue(e.getMessage().contains("cycle"), e.getMessage());
        assertTrue(e.getMessage().contains("a -> b -> a"), e.getMessage());
    }

    @Test void refusesSelfReference() {
        DialogSyntaxException e = assertThrows(DialogSyntaxException.class, () -> DialogInheritance.resolve("a",
                files("a", tree("extends", "a"))));
        assertTrue(e.getMessage().contains("a -> a"), e.getMessage());
    }

    @Test void refusesUnknownParentAndBadNames() {
        assertTrue(assertThrows(DialogSyntaxException.class, () -> DialogInheritance.resolve("a",
                files("a", tree("extends", "missing")))).getMessage().contains("unknown dialog 'missing'"));
        assertTrue(assertThrows(DialogSyntaxException.class, () -> DialogInheritance.resolve("a",
                files("a", tree("extends", List.of("nope"))))).getMessage().contains("must be the name"));
        assertTrue(assertThrows(DialogSyntaxException.class, () -> DialogInheritance.resolve("a",
                files("a", tree("extends", "Not A Name")))).getMessage().contains("not a valid dialog name"));
    }

    @Test void refusesAChainLongerThanTheLimit() {
        Map<String, Map<String, Object>> chain = new LinkedHashMap<>();
        int size = DialogInheritance.MAX_DEPTH + 2;
        for (int i = 0; i < size; i++) {
            chain.put("d" + i, i + 1 < size ? tree("extends", "d" + (i + 1)) : tree("title", "end"));
        }
        DialogSyntaxException e = assertThrows(DialogSyntaxException.class,
                () -> DialogInheritance.resolve("d0", chain));
        assertTrue(e.getMessage().contains("longer than"), e.getMessage());
    }
}
