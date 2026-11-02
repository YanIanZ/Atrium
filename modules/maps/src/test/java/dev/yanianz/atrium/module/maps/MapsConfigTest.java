package dev.yanianz.atrium.module.maps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MapsConfigTest {
    @Test void defaultsKeepTheDocumentedValues() {
        MapsConfig config = new MapsConfig();
        MapsConfig.validate(config);
        assertEquals("atrium", config.markerSetId);
        assertEquals("atrium_warps", config.bluemapMarkerSetId);
        assertEquals("Atrium", config.markerSetLabel);
    }

    @Test void markerSetIdsAreSanitisedForTheBackends() {
        MapsConfig config = new MapsConfig();
        config.markerSetId = "  My Set  ";
        config.bluemapMarkerSetId = "!! BlueMap Layer !!";
        MapsConfig.validate(config);
        assertEquals("my_set", config.markerSetId);
        assertEquals("bluemap_layer", config.bluemapMarkerSetId);

        config.bluemapMarkerSetId = "???";
        MapsConfig.validate(config);
        assertEquals("atrium_warps", config.bluemapMarkerSetId, "blank ids fall back to the documented default");
    }

    @Test void numericOptionsAreClampedInsteadOfRejected() {
        MapsConfig config = new MapsConfig();
        config.refreshIntervalSeconds = 0;
        config.visibilityResyncSeconds = -5;
        config.portalMarkerRadius = 900;
        config.portalMarkerMax = 0;
        config.spawnAreaRadius = 900;
        config.pointMarkerRadius = 0;
        MapsConfig.validate(config);
        assertEquals(5, config.refreshIntervalSeconds);
        assertEquals(5, config.visibilityResyncSeconds);
        assertEquals(64, config.portalMarkerRadius);
        assertEquals(1, config.portalMarkerMax);
        assertEquals(64, config.spawnAreaRadius);
        assertEquals(1, config.pointMarkerRadius);
    }
}
