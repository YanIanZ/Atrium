package dev.yanianz.atrium.module.integrations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ProtectionCompositeTest {
    private final CompositeProtectionService service = new CompositeProtectionService(
            player -> false, IntegrationsConfig::new, LoggerFactory.getLogger("test"));

    @Test
    void noHooksAllowsEverything() {
        assertTrue(service.canBuild(null, null));
        assertTrue(service.canBreak(null, null));
        assertTrue(service.canInteract(null, null));
        assertTrue(service.canUseCommandAt(null, null, "home"));
    }

    @Test
    void allHooksMustAllow() {
        service.addHook(new FakeHook("a", true, true, true, true));
        service.addHook(new FakeHook("b", true, true, true, true));
        assertTrue(service.canBuild(null, null));

        service.addHook(new FakeHook("c", false, true, true, true));
        assertFalse(service.canBuild(null, null));
        assertTrue(service.canBreak(null, null));
        assertTrue(service.canInteract(null, null));
        assertTrue(service.canUseCommandAt(null, null, "home"));
    }

    @Test
    void everyHookIsAskedEvenAfterADenial() {
        FakeHook deny = new FakeHook("deny", false, false, false, false);
        FakeHook allow = new FakeHook("allow", true, true, true, true);
        service.addHook(deny);
        service.addHook(allow);
        assertFalse(service.canBuild(null, null));
        assertTrue(deny.called);
        assertTrue(allow.called);
    }

    @Test
    void failingHookDenies() {
        service.addHook(new FakeHook("broken", true, true, true, true) {
            @Override
            public boolean canBuild(Player player, Location location) {
                throw new IllegalStateException("boom");
            }
        });
        assertFalse(service.canBuild(null, null));
    }

    @Test
    void disabledCheckIsSkipped() {
        IntegrationsConfig config = new IntegrationsConfig();
        config.checkBuild = false;
        CompositeProtectionService skipping = new CompositeProtectionService(player -> false, () -> config,
                LoggerFactory.getLogger("test"));
        skipping.addHook(new FakeHook("deny", false, false, false, false));
        assertTrue(skipping.canBuild(null, null));
    }

    private static class FakeHook implements ProtectionHook {
        private final String id;
        private final boolean build;
        private final boolean breakBlock;
        private final boolean interact;
        private final boolean command;
        boolean called;

        FakeHook(String id, boolean build, boolean breakBlock, boolean interact, boolean command) {
            this.id = id;
            this.build = build;
            this.breakBlock = breakBlock;
            this.interact = interact;
            this.command = command;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return id;
        }

        @Override
        public String version() {
            return "test";
        }

        @Override
        public boolean canBuild(Player player, Location location) {
            called = true;
            return build;
        }

        @Override
        public boolean canBreak(Player player, Block block) {
            called = true;
            return breakBlock;
        }

        @Override
        public boolean canInteract(Player player, Location location) {
            called = true;
            return interact;
        }

        @Override
        public boolean canUseCommandAt(Player player, Location location, String command) {
            called = true;
            return this.command;
        }
    }

}
