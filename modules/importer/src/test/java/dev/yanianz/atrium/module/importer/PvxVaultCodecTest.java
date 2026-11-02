package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * The PlayerVaultsX format decision. Accepted: the current base64 payload of a slot count plus
 * length-prefixed gzip item NBT (the same item bytes as {@code ItemStack#serializeAsBytes}). Refused:
 * Java object serialization ({@code AC ED}) and YAML item maps.
 */
class PvxVaultCodecTest {
    private static final byte[] GZIP_HEADER = {0x1F, (byte) 0x8B, 0x08, 0x00};
    private static final byte[] JAVA_STREAM = {(byte) 0xAC, (byte) 0xED, 0x00, 0x05, 0x73, 0x72};

    /** slot count = 3, slot 1 has gzip item bytes, slot 2 is empty, slot 3 has gzip bytes. */
    private static byte[] currentFormat() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(3);
            out.writeInt(GZIP_HEADER.length);
            out.write(GZIP_HEADER);
            out.writeInt(0);
            out.writeInt(GZIP_HEADER.length);
            out.write(GZIP_HEADER);
        }
        return bytes.toByteArray();
    }

    @Test
    void acceptsCurrentPlayerVaultsPayload() throws Exception {
        PvxVaultCodec.Decoded decoded = PvxVaultCodec.decode(currentFormat());

        assertTrue(decoded.ok());
        assertNull(decoded.refused());
        assertEquals(3, decoded.slots());
        assertEquals(3, decoded.items().size());
        assertNotNull(decoded.items().get(0));
        assertNull(decoded.items().get(1), "a zero length is an empty slot");
        assertNotNull(decoded.items().get(2));
        assertEquals(PvxVaultCodec.Format.PAPER_NBT, PvxVaultCodec.format(decoded.items().get(0)));
    }

    @Test
    void refusesJavaObjectSerialization() {
        PvxVaultCodec.Decoded decoded = PvxVaultCodec.decode(JAVA_STREAM);

        assertFalse(decoded.ok());
        assertEquals(PvxVaultCodec.Format.JAVA_SERIALIZED, decoded.refused());
        assertTrue(decoded.reason().contains("Java object serialization"));
        assertTrue(decoded.reason().contains("arbitrary classes"));
    }

    @Test
    void refusesAJavaSerializedSlotInsideAnOtherwiseGoodVault() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(2);
            out.writeInt(GZIP_HEADER.length);
            out.write(GZIP_HEADER);
            out.writeInt(JAVA_STREAM.length);
            out.write(JAVA_STREAM);
        }

        PvxVaultCodec.Decoded decoded = PvxVaultCodec.decode(bytes.toByteArray());
        assertFalse(decoded.ok(), "one unreadable slot refuses the whole vault");
        assertEquals(PvxVaultCodec.Format.JAVA_SERIALIZED, decoded.refused());
    }

    @Test
    void rejectsBrokenPayloadsInsteadOfGuessing() throws IOException {
        assertEquals(0, PvxVaultCodec.decode(null).slots());
        assertFalse(PvxVaultCodec.decode(new byte[0]).ok());
        assertFalse(PvxVaultCodec.decode(new byte[] {0, 0, 0, 5}).ok());

        ByteArrayOutputStream zero = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(zero)) {
            out.writeInt(0);
        }
        assertFalse(PvxVaultCodec.decode(zero.toByteArray()).ok());

        ByteArrayOutputStream tooBig = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(tooBig)) {
            out.writeInt(55);
        }
        assertFalse(PvxVaultCodec.decode(tooBig.toByteArray()).ok());

        assertFalse(PvxVaultCodec.decode(java.util.Base64.getDecoder()
                .decode("AAAAAAAAAAAA")).ok(), "slot count without entries is truncated");
        assertEquals(PvxVaultCodec.Format.UNKNOWN, PvxVaultCodec.format("not an item".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsVaultKeysOfBothPlayerVaultsLayouts() {
        assertEquals(3, PvxVaultFile.number("vault3"));
        assertEquals(3, PvxVaultFile.number("3"));
        assertEquals(3, PvxVaultFile.number(" Vault3 "));
        assertEquals(0, PvxVaultFile.number("vault0"));
        assertEquals(0, PvxVaultFile.number("55"));
        assertEquals(0, PvxVaultFile.number("backups"));
        assertEquals(0, PvxVaultFile.number(null));
    }

    @Test
    void separatesYamlItemMapsFromBase64Values(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.yml");
        Files.writeString(file, """
                vault1: H4sIAAAAAAAA
                vault2:
                  ==: org.bukkit.inventory.ItemStack
                  type: DIAMOND
                  amount: 5
                """);
        ConfigurationNode node = YamlConfigurationLoader.builder().path(file).build().load();

        List<PvxVaultFile.Entry> entries = PvxVaultFile.parse(node);
        assertEquals(2, entries.size());
        assertEquals(1, entries.get(0).number());
        assertFalse(entries.get(0).nonText());
        assertEquals("H4sIAAAAAAAA", entries.get(0).data());
        assertEquals(2, entries.get(1).number());
        assertTrue(entries.get(1).nonText(), "a map value is not the base64 format and must be refused");
    }
}
