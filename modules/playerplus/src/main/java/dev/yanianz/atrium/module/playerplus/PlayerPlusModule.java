package dev.yanianz.atrium.module.playerplus;

import dev.yanianz.atrium.api.player.FlightService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Set;

/**
 * Phase-five player utilities: temporary flight, paid flight time, cuffs, pet gifting and hunger control.
 * Flight is always taken through the published {@link FlightService}; this module never toggles
 * {@code allowFlight} itself.
 */
public final class PlayerPlusModule implements AtriumModule {
    private ModuleContext ctx;
    private ConfigHandle<PlayerPlusConfig> config;
    private PlayerPlusStore store;
    private TflyManager tfly;
    private FlightChargeManager charge;
    private CuffManager cuff;
    private GiftPetManager giftPet;

    @Override
    public String id() {
        return "playerplus";
    }

    @Override
    public String description() {
        return "Temporary and charged flight, cuffs, pet gifting, hunger and saturation";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("player");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.config = ctx.config(PlayerPlusConfig.class, PlayerPlusConfig::new);
        ctx.migrate(PlayerPlusStore.MIGRATIONS);
        this.store = new PlayerPlusStore(ctx.database(), () -> PlayerPlusState.defaults(config.get()));
        ctx.component(store.component);

        FlightService flight = ctx.services().get(FlightService.class).orElse(null);
        if (flight == null) {
            ctx.logger().warn("FlightService is not available; /tfly and charged flight are disabled until "
                    + "the player module is enabled");
        }
        this.tfly = new TflyManager(ctx, config, store, flight);
        this.charge = new FlightChargeManager(ctx, config, store, flight);
        this.cuff = new CuffManager(ctx, config, store);
        this.giftPet = new GiftPetManager(ctx, config);

        ctx.listen(tfly);
        ctx.listen(charge);
        ctx.listen(cuff);
        ctx.listen(giftPet);
        new PlayerPlusCommands(ctx, config, store, tfly, charge, cuff, giftPet).register();
    }

    @Override
    public void disable() {
        if (tfly != null) {
            tfly.disable();
        }
        if (charge != null) {
            charge.disable();
        }
        if (cuff != null) {
            cuff.disable();
        }
        if (giftPet != null) {
            giftPet.disable();
        }
    }
}
