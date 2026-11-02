plugins {
    id("atrium.module")
}

description = "NPCs: native Mannequins, Citizens and FancyNpcs backends with skins, equipment, poses, scale, nameplates and click actions (/npc)."

dependencies {
    // Optional backends. Class files live on the plugin classpath only; a missing plugin never loads them.
    compileOnly("net.citizensnpcs:citizens-main:2.0.44-SNAPSHOT") { isTransitive = false }
    compileOnly("de.oliver:FancyNpcs:2.11.0") { isTransitive = false }
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
