plugins { id("atrium.module") }

description = "Item worth, /sell, /sellgui and sell log"

dependencies {
    testImplementation(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
