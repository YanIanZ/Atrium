package dev.yanianz.atrium.module.kits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KitUsageCodecTest {
    @Test
    void roundTrip() throws Exception {
        KitUsage usage = new KitUsage(Map.of("starter", new KitUsage.Claim(1_700_000_000_000L, 3),
                "vip", new KitUsage.Claim(0, Integer.MAX_VALUE)));
        assertEquals(usage, KitUsageCodec.INSTANCE.decode(KitUsageCodec.INSTANCE.encode(usage)));
        assertEquals(KitUsage.EMPTY, KitUsageCodec.INSTANCE.decode(KitUsageCodec.INSTANCE.encode(KitUsage.EMPTY)));
    }

    @Test
    void rejectsCorruptData() throws Exception {
        byte[] good = KitUsageCodec.INSTANCE.encode(new KitUsage(Map.of("a", new KitUsage.Claim(5, 1))));
        byte[] format = good.clone();
        format[0] = 2;
        assertThrows(IOException.class, () -> KitUsageCodec.INSTANCE.decode(format));
        assertThrows(IOException.class, () -> KitUsageCodec.INSTANCE.decode(Arrays.copyOf(good, good.length - 1)));
        assertThrows(IOException.class, () -> KitUsageCodec.INSTANCE.decode(Arrays.copyOf(good, good.length + 2)));
    }
}
