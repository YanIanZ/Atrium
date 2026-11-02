// Shared Java setup for every Atrium project: Java 25 toolchain, UTF-8, -parameters, JUnit 6.
plugins {
    `java-library`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
}

tasks.withType<Javadoc>().configureEach { enabled = false }

dependencies {
    compileOnly(libs.findLibrary("paper-api").get())
    testImplementation(libs.findLibrary("paper-api").get())
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
