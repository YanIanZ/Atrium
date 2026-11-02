plugins {
    id("atrium.module")
}

description = "PlaceholderAPI expansion exposing Atrium values as %atrium_*% placeholders"

dependencies {
    compileOnly(libs.placeholderapi)
}
