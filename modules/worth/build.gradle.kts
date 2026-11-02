plugins { id("atrium.module") }

description = "Item worth, /sell, /sellgui and sell log"

dependencies {
    // Optional price engines. The hook classes are only loaded when the matching plugin is enabled.
    compileOnly("com.github.Gypopo:EconomyShopGUI-API:1.11.0") { isTransitive = false }
    compileOnly("com.github.brcdev-minecraft:shopgui-api:3.2.0") { isTransitive = false }
    testImplementation(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
