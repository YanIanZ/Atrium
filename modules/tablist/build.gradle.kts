plugins {
    id("atrium.module")
}

description = "Tab list header/footer, per-group player list names and group sorting; hosts the shared display placeholder engine."

dependencies {
    compileOnly(libs.placeholderapi)
}
