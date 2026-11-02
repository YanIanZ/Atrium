plugins {
    id("atrium.module")
}

description = "Rank ladder with playtime, money and permission requirements"

dependencies {
    compileOnly("net.luckperms:api:5.5")
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
