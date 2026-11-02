plugins { id("atrium.module") }

description = "In-game defined kits with cooldowns, costs and previews"

dependencies {
    testImplementation(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
