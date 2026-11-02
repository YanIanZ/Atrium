package dev.yanianz.atrium.module.mobs;

import dev.yanianz.atrium.api.protection.ProtectionService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.menu.Button;
import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.menu.Menu;
import dev.yanianz.atrium.core.menu.MenuClick;
import dev.yanianz.atrium.core.menu.MenuProvider;
import dev.yanianz.atrium.core.menu.PagedMenu;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The spawner editor: a small chest GUI that changes delay, spawn count, ranges and the spawned type.
 *
 * <p>The menu never touches the spawner from the viewer's thread. Every change is a single atomic step on the
 * region owning the block, and the values shown in the menu are a snapshot taken on that same thread.
 */
final class SpawnerEditor {
    private final ModuleContext ctx;
    private final ConfigHandle<MobsConfig> config;
    private final @Nullable ProtectionService protection;

    SpawnerEditor(ModuleContext ctx, ConfigHandle<MobsConfig> config, @Nullable ProtectionService protection) {
        this.ctx = ctx;
        this.config = config;
        this.protection = protection;
    }

    boolean enabled() {
        return config.get().spawners.editor.enabled;
    }

    /** Called on the player's thread. */
    void open(Player player, Block block) {
        Location location = block.getLocation().clone();
        ctx.scheduler().supplyAt(location, () -> read(location.getBlock())).whenComplete((settings, error) -> {
            if (error != null) {
                ctx.logger().warn("Opening the spawner editor for {} failed", player.getName(), error);
                ctx.messages().send(player, "mobs.spawner-failed");
                return;
            }
            if (settings == null) {
                ctx.messages().send(player, "mobs.not-spawner");
                return;
            }
            ctx.menus().open(player, new Session(location, settings));
        });
    }

    private final class Session implements MenuProvider {
        private final Location location;
        private volatile SpawnerSettings settings;

        private Session(Location location, SpawnerSettings settings) {
            this.location = location;
            this.settings = settings;
        }

        @Override
        public Menu render(Player viewer) {
            SpawnerSettings snapshot = settings;
            Component title = ctx.messages().render(viewer, "mobs.spawner-editor-title");
            Menu.Builder menu = Menu.builder(title, 3);
            menu.button(10, knob(viewer, Material.CLOCK, "mobs.spawner-editor-delay",
                    snapshot.delay(), SpawnerSettings.Field.DELAY, this));
            menu.button(11, knob(viewer, Material.REPEATER, "mobs.spawner-editor-min-delay",
                    snapshot.minDelay(), SpawnerSettings.Field.MIN_DELAY, this));
            menu.button(12, knob(viewer, Material.COMPARATOR, "mobs.spawner-editor-max-delay",
                    snapshot.maxDelay(), SpawnerSettings.Field.MAX_DELAY, this));
            menu.button(13, knob(viewer, Material.SPAWNER, "mobs.spawner-editor-count",
                    snapshot.spawnCount(), SpawnerSettings.Field.SPAWN_COUNT, this));
            menu.button(14, knob(viewer, Material.ENDER_EYE, "mobs.spawner-editor-player-range",
                    snapshot.playerRange(), SpawnerSettings.Field.PLAYER_RANGE, this));
            menu.button(15, knob(viewer, Material.TARGET, "mobs.spawner-editor-spawn-range",
                    snapshot.spawnRange(), SpawnerSettings.Field.SPAWN_RANGE, this));
            menu.button(16, knob(viewer, Material.BEACON, "mobs.spawner-editor-nearby",
                    snapshot.maxNearby(), SpawnerSettings.Field.MAX_NEARBY, this));
            menu.button(22, typeButton(viewer, snapshot, this));
            menu.button(26, Button.of(ItemBuilder.of(Material.BARRIER)
                    .name(ctx.messages().render(viewer, "core.menu.close")).build(), MenuClick::close));
            return menu.build();
        }
    }

    private Button knob(Player viewer, Material material, String nameKey, int value,
            SpawnerSettings.Field field, Session session) {
        ItemStack icon = ItemBuilder.of(material)
                .name(ctx.messages().render(viewer, nameKey))
                .lore(ctx.messages().render(viewer, "mobs.spawner-editor-value", Msg.number("value", value)),
                        ctx.messages().render(viewer, "mobs.spawner-editor-hint"))
                .build();
        return Button.of(icon, click -> adjust(session, field, click));
    }

    private Button typeButton(Player viewer, SpawnerSettings settings, Session session) {
        EntityType type = settings.typeKey() == null ? null : EntityTypes.parse(settings.typeKey()).orElse(null);
        ItemBuilder icon = type == null
                ? ItemBuilder.of(Material.SPAWNER)
                : ItemBuilder.from(SpawnerItems.item(type));
        Component name = type == null
                ? ctx.messages().render(viewer, "mobs.spawner-editor-no-type")
                : Component.translatable(type.translationKey());
        ItemStack stack = icon.name(ctx.messages().render(viewer, "mobs.spawner-editor-type"))
                .lore(name, ctx.messages().render(viewer, "mobs.spawner-editor-type-hint"))
                .build();
        return Button.of(stack, click -> ctx.menus().push(click.player(), typePicker(session, click.player())));
    }

    private MenuProvider typePicker(Session session, Player owner) {
        Component title = ctx.messages().render(owner, "mobs.spawner-editor-type-picker");
        return PagedMenu.builder(ctx.menus(), title)
                .rows(6)
                .content(viewer -> {
                    List<Button> buttons = new ArrayList<>();
                    for (EntityType type : EntityTypes.spawnableTypes()) {
                        if (!EntityTypes.spawnerType(type)
                                || !viewer.hasPermission("atrium.setspawner." + EntityTypes.node(type))) {
                            continue;
                        }
                        ItemStack icon = ItemBuilder.from(SpawnerItems.item(type))
                                .name(Component.translatable(type.translationKey()))
                                .build();
                        buttons.add(Button.of(icon, click -> changeType(session, click.player(), type)));
                    }
                    return buttons;
                })
                .build();
    }

    private void adjust(Session session, SpawnerSettings.Field field, MenuClick click) {
        int step = click.isShift() ? 10 : 1;
        int delta = click.isRight() ? -step : step;
        Player player = click.player();
        ctx.scheduler().supplyAt(session.location, () -> mutate(session.location, s -> s.shift(field, delta)))
                .whenComplete((settings, error) -> {
                    if (error != null) {
                        ctx.logger().warn("Updating a spawner for {} failed", player.getName(), error);
                        ctx.messages().send(player, "mobs.spawner-failed");
                        return;
                    }
                    if (settings == null) {
                        ctx.messages().send(player, "mobs.not-spawner");
                        ctx.menus().close(player);
                        return;
                    }
                    session.settings = settings;
                    ctx.menus().refresh(player);
                });
    }

    private void changeType(Session session, Player player, EntityType type) {
        ctx.scheduler().supplyAt(session.location, () -> {
            if (protection != null && !protection.canBuild(player, session.location)) {
                return TypeResult.PROTECTED;
            }
            return switch (SpawnerListener.changeType(player, session.location.getBlock(), type)) {
                case CHANGED -> TypeResult.CHANGED;
                case DENIED -> TypeResult.DENIED;
                case NOT_SPAWNER -> TypeResult.NOT_SPAWNER;
            };
        }).whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().warn("Changing a spawner for {} failed", player.getName(), error);
                ctx.messages().send(player, "mobs.spawner-failed");
                return;
            }
            switch (result) {
                case CHANGED -> {
                    session.settings = session.settings.withType(EntityTypes.name(type));
                    ctx.messages().send(player, "mobs.spawner-set", Msg.unparsed("type", EntityTypes.name(type)));
                    ctx.menus().back(player);
                    ctx.menus().refresh(player);
                }
                case DENIED -> ctx.messages().send(player, "mobs.spawner-denied");
                case PROTECTED -> ctx.messages().send(player, "mobs.spawner-protected");
                case NOT_SPAWNER -> {
                    ctx.messages().send(player, "mobs.not-spawner");
                    ctx.menus().close(player);
                }
            }
        });
    }

    private enum TypeResult { CHANGED, DENIED, PROTECTED, NOT_SPAWNER }

    /** Block thread. */
    private static @Nullable SpawnerSettings read(@Nullable Block block) {
        if (block == null || !(block.getState() instanceof CreatureSpawner spawner)) {
            return null;
        }
        return read(spawner);
    }

    private static SpawnerSettings read(CreatureSpawner spawner) {
        EntityType type = spawner.getSpawnedType();
        return new SpawnerSettings(spawner.getDelay(), spawner.getMinSpawnDelay(), spawner.getMaxSpawnDelay(),
                spawner.getSpawnCount(), spawner.getRequiredPlayerRange(), spawner.getSpawnRange(),
                spawner.getMaxNearbyEntities(), type == null ? null : EntityTypes.name(type));
    }

    /** Block thread. Applies one change and returns the values that are now stored. */
    private static @Nullable SpawnerSettings mutate(Location location, UnaryOperator<SpawnerSettings> change) {
        Block block = location.getBlock();
        if (!(block.getState() instanceof CreatureSpawner spawner)) {
            return null;
        }
        SpawnerSettings updated = change.apply(read(spawner));
        spawner.setDelay(updated.delay());
        spawner.setMinSpawnDelay(updated.minDelay());
        spawner.setMaxSpawnDelay(updated.maxDelay());
        spawner.setSpawnCount(updated.spawnCount());
        spawner.setRequiredPlayerRange(updated.playerRange());
        spawner.setSpawnRange(updated.spawnRange());
        spawner.setMaxNearbyEntities(updated.maxNearby());
        spawner.update(true, false);
        return updated;
    }
}
