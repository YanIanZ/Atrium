package dev.yanianz.atrium.module.particles;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.menu.Button;
import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.menu.Menu;
import dev.yanianz.atrium.core.menu.MenuClick;
import dev.yanianz.atrium.core.menu.MenuService;
import dev.yanianz.atrium.core.menu.PagedMenu;
import dev.yanianz.atrium.core.menu.SlotLayout;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.module.particles.shape.Style;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The /particles chest menus, built entirely on {@code ctx.menus()}. */
final class ParticleMenu {
    private static final int ROOT_ROWS = 6;

    private final ModuleContext ctx;
    private final ParticlesModule module;
    private final ConfigHandle<ParticlesConfig> config;
    private final Messages messages;
    private final MenuService menus;

    ParticleMenu(ModuleContext ctx, ParticlesModule module, ConfigHandle<ParticlesConfig> config) {
        this.ctx = ctx;
        this.module = module;
        this.config = config;
        this.messages = ctx.messages();
        this.menus = ctx.menus();
    }

    void openRoot(Player viewer) {
        ParticleProfile profile = module.profileOf(viewer);
        Menu.Builder builder = Menu.builder(text(viewer, "menu.title"), ROOT_ROWS);
        int slot = 0;
        for (ParticleEffect effect : profile.effects()) {
            if (slot >= 8) {
                break;
            }
            builder.button(slot++, effectButton(viewer, effect));
        }
        if (profile.effects().isEmpty()) {
            builder.item(4, ItemBuilder.of(Material.SUGAR)
                    .name(text(viewer, "menu.active-empty-name"))
                    .lore(text(viewer, "menu.active-empty-lore"))
                    .hideDetails()
                    .build());
        }
        builder.button(45, Button.of(ItemBuilder.of(Material.BOOK)
                .name(text(viewer, "menu.browse-name"))
                .lore(text(viewer, "menu.browse-lore"))
                .hideDetails()
                .build(), click -> click.open(categoryMenu(click.player()))));
        builder.button(46, presetButton(viewer));
        builder.button(47, hideOthersButton(viewer, profile));
        builder.button(48, hiddenButton(viewer, profile));
        builder.button(53, closeButton(viewer));
        menus.open(viewer, builder.build());
    }

    private Button effectButton(Player viewer, ParticleEffect effect) {
        ItemStack icon = ItemBuilder.of(icon(effect.style()))
                .name(text(viewer, "menu.effect-name", Msg.unparsed("style", effect.style().key())))
                .lore(List.of(
                        text(viewer, "menu.effect-lore",
                                Msg.unparsed("particle", ParticleNames.key(effect.particle())),
                                Msg.unparsed("colour", ParticleColours.hex(effect.colours().primary()))),
                        text(viewer, "menu.effect-click")))
                .glint(true)
                .hideDetails()
                .build();
        return Button.of(icon, click -> {
            module.removeSelf(click.player(), effect.style());
            openRoot(click.player());
        });
    }

    private Button presetButton(Player viewer) {
        boolean hasPresets = config.get().presets != null && !config.get().presets.isEmpty();
        ItemBuilder icon = ItemBuilder.of(hasPresets ? Material.NETHER_STAR : Material.GRAY_DYE)
                .name(text(viewer, "menu.presets-name"))
                .lore(text(viewer, hasPresets ? "menu.presets-lore" : "menu.presets-empty"))
                .hideDetails();
        if (!hasPresets) {
            return Button.of(icon.build());
        }
        return Button.of(icon.build(), click -> click.open(presetMenu(click.player())));
    }

    private Button hideOthersButton(Player viewer, ParticleProfile profile) {
        boolean hidden = profile.hideOthers();
        return Button.of(ItemBuilder.of(hidden ? Material.GRAY_DYE : Material.ENDER_EYE)
                .name(text(viewer, "menu.hideothers-name"))
                .lore(text(viewer, hidden ? "menu.hideothers-on" : "menu.hideothers-off"))
                .hideDetails()
                .build(), click -> {
                    module.toggleHideOthers(click.player());
                    openRoot(click.player());
                });
    }

    private Button hiddenButton(Player viewer, ParticleProfile profile) {
        boolean hidden = profile.hidden();
        return Button.of(ItemBuilder.of(hidden ? Material.GLASS : Material.LIME_DYE)
                .name(text(viewer, "menu.hidden-name"))
                .lore(text(viewer, hidden ? "menu.hidden-on" : "menu.hidden-off"))
                .hideDetails()
                .build(), click -> {
                    module.toggleHidden(click.player());
                    openRoot(click.player());
                });
    }

    private Menu categoryMenu(Player viewer) {
        Menu.Builder builder = Menu.builder(text(viewer, "menu.category-title"), 3);
        int[] slots = {11, 13, 15};
        int index = 0;
        for (Style.Category category : Style.Category.values()) {
            builder.button(slots[index++], categoryButton(viewer, category));
        }
        builder.button(18, backButton(viewer));
        builder.button(26, closeButton(viewer));
        return builder.build();
    }

    private Button categoryButton(Player viewer, Style.Category category) {
        int count = Style.inCategory(category).size();
        return Button.of(ItemBuilder.of(categoryIcon(category))
                .name(text(viewer, "menu.category-name", Msg.unparsed("category", category.key())))
                .lore(text(viewer, "menu.category-lore",
                        Msg.unparsed("category", category.key()), Msg.number("count", count)))
                .hideDetails()
                .build(), click -> click.open(styleMenu(click.player(), category)));
    }

    private PagedMenu styleMenu(Player viewer, Style.Category category) {
        return PagedMenu.builder(menus, text(viewer, "menu.styles-title", Msg.unparsed("category", category.key())))
                .rows(6)
                .content(player -> {
                    ParticleProfile profile = module.profileOf(player);
                    List<Button> buttons = new ArrayList<>();
                    for (Style style : Style.inCategory(category)) {
                        buttons.add(styleButton(player, style, profile));
                    }
                    return buttons;
                })
                .build();
    }

    private Button styleButton(Player viewer, Style style, ParticleProfile profile) {
        boolean active = profile.effect(style) != null;
        boolean allowed = module.canUseStyle(viewer, style);
        ItemBuilder icon = ItemBuilder.of(icon(style))
                .name(text(viewer, "menu.style-name", Msg.unparsed("style", style.key())))
                .lore(List.of(
                        text(viewer, "menu.style-lore",
                                Msg.unparsed("particle", ParticleNames.key(module.defaultParticle(style))),
                                Msg.number("radius", config.get().radius(style)),
                                Msg.number("density", config.get().density(style)),
                                Msg.number("speed", config.get().speed(style))),
                        text(viewer, active ? "menu.style-active" : "menu.style-inactive"),
                        text(viewer, allowed ? "menu.style-click" : "menu.style-locked")))
                .glint(active)
                .hideDetails();
        if (!allowed) {
            return Button.of(icon.build(), click -> {
                messages.send(click.player(), "particles.style-denied", Msg.unparsed("style", style.key()));
                click.error();
            });
        }
        return Button.of(icon.build(), click -> click.open(particleMenu(click.player(), style)));
    }

    private PagedMenu particleMenu(Player viewer, Style style) {
        return PagedMenu.builder(menus, text(viewer, "menu.particles-title", Msg.unparsed("style", style.key())))
                .rows(6)
                .content(player -> {
                    List<Button> buttons = new ArrayList<>();
                    for (Particle particle : Particle.values()) {
                        if (!ParticleData.supported(particle) || !module.canUseParticle(player, particle)) {
                            continue;
                        }
                        buttons.add(particleButton(player, style, particle));
                    }
                    return buttons;
                })
                .build();
    }

    private Button particleButton(Player viewer, Style style, Particle particle) {
        ItemStack icon = ItemBuilder.of(Material.FIREWORK_STAR)
                .name(text(viewer, "menu.particle-name", Msg.unparsed("particle", ParticleNames.key(particle))))
                .lore(text(viewer, "menu.particle-lore"))
                .hideDetails()
                .build();
        return Button.of(icon, click -> click.open(colourMenu(click.player(), style, particle)));
    }

    private Menu colourMenu(Player viewer, Style style, Particle particle) {
        Menu.Builder builder = Menu.builder(text(viewer, "menu.colours-title",
                Msg.unparsed("style", style.key()), Msg.unparsed("particle", ParticleNames.key(particle))), 5);
        int[] slots = SlotLayout.framedContent(5);
        List<String> fixed = config.get().fixedColours;
        for (int i = 0; i < fixed.size() && i < slots.length; i++) {
            Color colour = ParticleColours.parse(fixed.get(i), Color.WHITE);
            int slot = slots[i];
            builder.button(slot, Button.of(ItemBuilder.of(Material.FIREWORK_STAR)
                    .name(text(viewer, "menu.colour-name", Msg.unparsed("colour", ParticleColours.hex(colour))))
                    .lore(text(viewer, "menu.colour-fixed"))
                    .hideDetails()
                    .build(), click -> {
                        ParticleColours colours = new ParticleColours(ParticleColours.Mode.FIXED, colour, colour);
                        module.applySelf(click.player(), module.effectFor(style, particle, colours));
                        openRoot(click.player());
                    }));
        }
        builder.button(38, colourModeButton(viewer, "menu.colour-rainbow", "menu.colour-rainbow-lore", style,
                particle, ParticleColours.Mode.RAINBOW));
        builder.button(40, colourModeButton(viewer, "menu.colour-gradient", "menu.colour-gradient-lore", style,
                particle, ParticleColours.Mode.GRADIENT));
        builder.button(36, backButton(viewer));
        builder.button(44, closeButton(viewer));
        return builder.build();
    }

    private Button colourModeButton(Player viewer, String nameKey, String loreKey, Style style, Particle particle,
            ParticleColours.Mode mode) {
        return Button.of(ItemBuilder.of(mode == ParticleColours.Mode.RAINBOW ? Material.NETHER_STAR
                        : Material.PRISMARINE_CRYSTALS)
                .name(text(viewer, nameKey))
                .lore(text(viewer, loreKey))
                .hideDetails()
                .build(), click -> {
                    ParticleColours base = module.colours(style, null);
                    ParticleColours colours = new ParticleColours(mode, base.primary(), base.secondary());
                    module.applySelf(click.player(), module.effectFor(style, particle, colours));
                    openRoot(click.player());
                });
    }

    private PagedMenu presetMenu(Player viewer) {
        return PagedMenu.builder(menus, text(viewer, "menu.presets-title"))
                .rows(6)
                .content(player -> {
                    List<Button> buttons = new ArrayList<>();
                    if (config.get().presets != null) {
                        for (Map.Entry<String, ParticlesConfig.Preset> entry : config.get().presets.entrySet()) {
                            if (entry.getKey() == null || entry.getValue() == null) {
                                continue;
                            }
                            buttons.add(presetItem(player, entry.getKey(), entry.getValue()));
                        }
                    }
                    return buttons;
                })
                .build();
    }

    private Button presetItem(Player viewer, String name, ParticlesConfig.Preset preset) {
        int count = preset.effects == null ? 0 : preset.effects.size();
        return Button.of(ItemBuilder.of(Material.NETHER_STAR)
                .name(text(viewer, "menu.preset-name", Msg.unparsed("name", name)))
                .lore(text(viewer, "menu.preset-lore", Msg.number("count", count)))
                .hideDetails()
                .build(), click -> {
                    module.applyPreset(click.player(), name);
                    openRoot(click.player());
                });
    }

    // ---- small helpers ----

    private Button backButton(Player viewer) {
        return Button.of(ItemBuilder.of(Material.ARROW)
                .name(menus.text(viewer, "core.menu.back"))
                .hideDetails()
                .build(), MenuClick::back);
    }

    private Button closeButton(Player viewer) {
        return Button.of(ItemBuilder.of(Material.BARRIER)
                .name(menus.text(viewer, "core.menu.close"))
                .hideDetails()
                .build(), MenuClick::close);
    }

    private Component text(Player viewer, String key, TagResolver... tags) {
        return messages.render(viewer, "particles." + key, tags);
    }

    private static Material icon(Style style) {
        return switch (style) {
            case HALO -> Material.GOLD_NUGGET;
            case HELIX -> Material.END_ROD;
            case ORBIT -> Material.FIREWORK_STAR;
            case WINGS -> Material.ELYTRA;
            case CLOUD -> Material.WHITE_WOOL;
            case CAPE -> Material.LEATHER_CHESTPLATE;
            case CROWN -> Material.GOLDEN_HELMET;
            case SPHERE -> Material.SLIME_BALL;
            case BEAM -> Material.BEACON;
            case PULSE_RING -> Material.HEART_OF_THE_SEA;
            case FOOTSTEPS -> Material.LEATHER_BOOTS;
            case TRAIL -> Material.BLAZE_POWDER;
            case QUADHELIX -> Material.AMETHYST_SHARD;
            case ARROWS_TRAIL -> Material.ARROW;
        };
    }

    private static Material categoryIcon(Style.Category category) {
        return switch (category) {
            case BODY -> Material.NETHERITE_CHESTPLATE;
            case MOVEMENT -> Material.FEATHER;
            case AMBIENT -> Material.WHITE_WOOL;
        };
    }
}
