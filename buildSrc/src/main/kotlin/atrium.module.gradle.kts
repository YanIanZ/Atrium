// Convention for feature modules under modules/<name>: compile against the API and core framework.
// The runtime libraries (Hikari, Caffeine, Configurate, JDBC drivers) are downloaded by the Paper
// plugin loader at startup, so modules only see them as compileOnly.
plugins {
    id("atrium.java-conventions")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    compileOnly(project(":atrium-api"))
    compileOnly(project(":atrium-core"))
    testImplementation(project(":atrium-api"))
    testImplementation(project(":atrium-core"))
    for (lib in listOf("caffeine", "configurate-yaml")) {
        compileOnly(libs.findLibrary(lib).get())
        testImplementation(libs.findLibrary(lib).get())
    }
}
