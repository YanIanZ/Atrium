package dev.yanianz.atrium.module.interactive;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory interactive commands and their block/entity bindings. Reads are lock-free (event threads); writes are
 * synchronized and only happen after storage succeeded. Pure, unit tested.
 */
final class InteractiveRegistry {
    private final Map<String, InteractiveCommand> commands = new ConcurrentHashMap<>();
    private final Map<BlockKey, String> blocks = new ConcurrentHashMap<>();
    private final Map<UUID, String> entities = new ConcurrentHashMap<>();

    Optional<InteractiveCommand> get(String name) {
        return Optional.ofNullable(commands.get(InteractiveCommand.key(name)));
    }

    List<InteractiveCommand> all() {
        List<InteractiveCommand> list = new ArrayList<>(commands.values());
        list.sort(Comparator.comparing(InteractiveCommand::key));
        return list;
    }

    Optional<InteractiveCommand> atBlock(BlockKey key) {
        String name = blocks.get(key);
        return name == null ? Optional.empty() : Optional.ofNullable(commands.get(name));
    }

    Optional<InteractiveCommand> forEntity(UUID uuid) {
        String name = entities.get(uuid);
        return name == null ? Optional.empty() : Optional.ofNullable(commands.get(name));
    }

    /** Name (key) the target is bound to. */
    Optional<String> boundTo(InteractiveCommand.TargetKind kind, String target) {
        return Optional.ofNullable(switch (kind) {
            case BLOCK -> BlockKey.decode(target).map(blocks::get).orElse(null);
            case ENTITY -> entities.get(parseUuid(target));
        });
    }

    List<BlockKey> blocksOf(String name) {
        String key = InteractiveCommand.key(name);
        return blocks.entrySet().stream().filter(e -> e.getValue().equals(key)).map(Map.Entry::getKey)
                .sorted(Comparator.comparing(BlockKey::encode)).toList();
    }

    List<UUID> entitiesOf(String name) {
        String key = InteractiveCommand.key(name);
        return entities.entrySet().stream().filter(e -> e.getValue().equals(key)).map(Map.Entry::getKey).sorted().toList();
    }

    synchronized void put(InteractiveCommand command) {
        commands.put(command.key(), command);
    }

    /** Removes the command and all of its bindings. */
    synchronized boolean remove(String name) {
        String key = InteractiveCommand.key(name);
        blocks.values().removeIf(key::equals);
        entities.values().removeIf(key::equals);
        return commands.remove(key) != null;
    }

    synchronized void bind(InteractiveCommand.TargetKind kind, String target, String name) {
        String key = InteractiveCommand.key(name);
        switch (kind) {
            case BLOCK -> BlockKey.decode(target).ifPresent(b -> blocks.put(b, key));
            case ENTITY -> entities.put(parseUuid(target), key);
        }
    }

    synchronized void unbind(InteractiveCommand.TargetKind kind, String target) {
        switch (kind) {
            case BLOCK -> BlockKey.decode(target).ifPresent(blocks::remove);
            case ENTITY -> entities.remove(parseUuid(target));
        }
    }

    synchronized void clear() {
        commands.clear();
        blocks.clear();
        entities.clear();
    }

    Collection<String> names() {
        return all().stream().map(InteractiveCommand::name).toList();
    }

    private static UUID parseUuid(String text) {
        return UUID.fromString(text);
    }
}
