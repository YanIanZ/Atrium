package dev.yanianz.atrium.module.commandrules;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;

/**
 * Reads one command label, including a namespace ({@code minecraft:tp}) and plugin names. Brigadier's
 * {@code word()} and {@code string()} both reject ':', so command rules use this small type instead.
 * The native type is only used by Paper to mirror the command tree; parsing is ours.
 */
public final class CommandLabelArgument implements CustomArgumentType<String, String> {
    private static final CommandLabelArgument INSTANCE = new CommandLabelArgument();
    private static final int MAX_LENGTH = 128;
    private static final SimpleCommandExceptionType EMPTY =
            new SimpleCommandExceptionType(() -> "Expected a command name");
    private static final SimpleCommandExceptionType TOO_LONG =
            new SimpleCommandExceptionType(() -> "Command name is too long");

    private CommandLabelArgument() {}

    public static CommandLabelArgument label() {
        return INSTANCE;
    }

    @Override
    public ArgumentType<String> getNativeType() {
        return StringArgumentType.string();
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
            reader.skip();
        }
        String value = reader.getString().substring(start, reader.getCursor());
        if (value.isEmpty()) {
            throw EMPTY.createWithContext(reader);
        }
        if (value.length() > MAX_LENGTH) {
            throw TOO_LONG.createWithContext(reader);
        }
        return value;
    }
}
