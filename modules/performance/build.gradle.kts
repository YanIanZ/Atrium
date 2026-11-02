plugins {
    id("atrium.module")
}

description = "Lag diagnostics, ground cleanup, entity limits, server list and view distance."

dependencies {
    // Provided by the server at runtime. Only the console filter uses it, and installation fails softly
    // (warning, console untouched) when the classes cannot be linked.
    compileOnly("org.apache.logging.log4j:log4j-core:2.26.1")
}
