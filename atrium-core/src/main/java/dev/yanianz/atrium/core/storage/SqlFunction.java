package dev.yanianz.atrium.core.storage;

import java.sql.SQLException;

@FunctionalInterface
public interface SqlFunction<I, O> {
    O apply(I input) throws SQLException;
}
