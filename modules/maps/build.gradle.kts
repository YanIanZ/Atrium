plugins {
    id("atrium.module")
}

description = "Web map integrations: BlueMap, squaremap and dynmap markers, with warp, spawn, portal and vanish bridges"

dependencies {
    // BlueMap ships the API on its own plugin classpath; nothing is bundled into the Atrium jar.
    compileOnly("de.bluecolored.bluemap:BlueMapAPI:2.7.2")
    compileOnly("xyz.jpenilla:squaremap-api:1.4.3") { isTransitive = false }
    compileOnly("us.dynmap:DynmapCoreAPI:3.8")
}
