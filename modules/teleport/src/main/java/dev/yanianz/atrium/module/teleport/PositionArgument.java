package dev.yanianz.atrium.module.teleport;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import io.papermc.paper.command.brigadier.argument.resolvers.FinePositionResolver;
import java.util.List;
import java.util.Collection;
import net.kyori.adventure.text.Component;

/**
 * {@code x y z} with {@code ~} support. The client sees the vanilla position argument (highlighting and
 * completion); the server parses it with {@link Position#parse} so relative parts can be resolved against
 * the player who is moved, on that player's own thread.
 */
final class PositionArgument implements CustomArgumentType<Position, FinePositionResolver> {
    private static final SimpleCommandExceptionType INVALID = new SimpleCommandExceptionType(
            MessageComponentSerializer.message().serialize(Component.text("Expected x y z (numbers or ~)")));

    @Override
    public Position parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                if (!reader.canRead() || reader.peek() != ' ') {
                    reader.setCursor(start);
                    throw INVALID.createWithContext(reader);
                }
                reader.skip();
                text.append(' ');
            }
            int tokenStart = reader.getCursor();
            while (reader.canRead() && reader.peek() != ' ') {
                reader.skip();
            }
            if (reader.getCursor() == tokenStart) {
                reader.setCursor(start);
                throw INVALID.createWithContext(reader);
            }
            text.append(reader.getString(), tokenStart, reader.getCursor());
        }
        return Position.parse(text.toString()).orElseThrow(() -> {
            reader.setCursor(start);
            return INVALID.createWithContext(reader);
        });
    }

    @Override
    public ArgumentType<FinePositionResolver> getNativeType() {
        return ArgumentTypes.finePosition(true);
    }

    @Override
    public Collection<String> getExamples() {
        return List.of("0 64 0", "~ ~ ~", "~10 70 ~-5");
    }
}
