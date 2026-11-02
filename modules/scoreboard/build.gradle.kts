plugins {
    id("atrium.module")
}

description = "Per-player sidebar scoreboards with animated titles, conditions and /sidebar toggle."

dependencies {
    // Shared display engine (placeholders, animations, toggles) lives in the tablist module's
    // dev.yanianz.atrium.module.display package; every module ships in the same plugin jar.
    compileOnly(project(":modules:tablist"))
    testImplementation(project(":modules:tablist"))
    // Optional backend used on Folia, where there is no scoreboard API. The packetevents plugin
    // provides these classes at runtime; the jar is never bundled.
    compileOnly("com.github.retrooper:packetevents-api:2.14.0")
    // Optional backend through the TAB plugin's scoreboard API; only TabSidebarBackend references it.
    compileOnly("com.github.NEZNAMY:TAB-API:5.5.0") { isTransitive = false }
}
