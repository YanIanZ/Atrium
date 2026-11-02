plugins {
    id("atrium.module")
}

description = "CMI and EssentialsX data importer and SQLite backup helper"

dependencies {
    compileOnly(libs.hikari)
    compileOnly(libs.sqlite)
    compileOnly(libs.mariadb)
    testImplementation(libs.sqlite)
    testImplementation(libs.mariadb)
    testRuntimeOnly(libs.hikari)
}
