package dev.yanianz.atrium.module.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UtilDumpsTest {
    @Test
    void commandsAreSortedAndOneRecordPerLine() {
        String dump = UtilDumps.commands("2026-01-01 10:00", List.of(
                new UtilDumps.CommandRow("zeta", List.of("z"), "Atrium", "atrium.z", "/zeta", "last"),
                new UtilDumps.CommandRow("alpha", List.of(), "server", "", "/alpha", "first\nsecond")));
        assertTrue(dump.indexOf("command: alpha") < dump.indexOf("command: zeta"));
        assertTrue(dump.contains("description: first second"));
        assertTrue(dump.startsWith("# Atrium commands dump"));
    }

    @Test
    void permissionsIncludeDefaultAndUnknownDescriptionsStayEmpty() {
        String dump = UtilDumps.permissions("now", List.of(
                new UtilDumps.PermissionRow("atrium.test", "op", null)));
        assertTrue(dump.contains("permission: atrium.test"));
        assertTrue(dump.contains("default: op"));
        assertTrue(dump.contains("description: \n"));
    }

    @Test
    void materialsAreSortedOnePerLine() {
        String dump = UtilDumps.materials("now", List.of(
                new UtilDumps.MaterialRow("STONE"),
                new UtilDumps.MaterialRow("AIR")));
        assertTrue(dump.contains("name\n"));
        assertTrue(dump.indexOf("AIR\n") < dump.indexOf("STONE\n"));
        assertTrue(dump.contains("STONE\n"));
    }

    @Test
    void infoIsSortedAndFlattened() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("zeta", "1");
        values.put("alpha", "two\nlines");
        String dump = UtilDumps.info("now", values);
        assertTrue(dump.indexOf("alpha: two lines") < dump.indexOf("zeta: 1"));
    }

    @Test
    void rowsAreCountedInTheHeader() {
        assertEquals(true, UtilDumps.materials("now", List.of()).contains("# rows: 0"));
    }
}
