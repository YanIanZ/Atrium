plugins {
    id("atrium.module")
}

description = "Tab list header/footer, per-group player list names and group sorting (native or through the TAB plugin); hosts the shared display placeholder engine."

dependencies {
    compileOnly(libs.placeholderapi)
    // Optional "tab" engine. Only the TAB hook classes reference this API, and they are loaded only when the
    // TAB plugin is enabled. Built against the last 5.x API; every call used here also exists in TAB 6.
    compileOnly("com.github.NEZNAMY:TAB-API:5.5.0") { isTransitive = false }
}
