package dev.yanianz.atrium.module.integrations;

import dev.yanianz.atrium.api.meta.MetaService;
import java.util.Map;

/** Provider-neutral metadata snapshot. */
record MetaSnapshot(MetaService.Meta meta, Map<String, String> values) {
    static final MetaSnapshot EMPTY = new MetaSnapshot(MetaService.Meta.EMPTY, Map.of());
}
