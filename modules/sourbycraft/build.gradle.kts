plugins {
    id("atrium.module")
}

description = "SourbyCraft integration: AWF MultiProfile state adapter, on-demand AWF worlds, async world unload, region metrics."

dependencies {
    // Baseline: the OFFICIAL SourbyCraft 26.2 API (GitHub branch 26.2, sources copied into compat/sourbyapi-26.2).
    // Compiling against it proves that Atrium only uses API members the official server has. The members of
    // newer local API builds (profile names/members/virtual ids, ProfileUid, ProfilePurse, awaitProfileReady,
    // adapter required()/applyMissing()) are optional extras found at runtime (ExtendedApi), so no local DEV
    // jar is needed. The hook classes referencing dev.iyanz.* are loaded only after the classes were found at
    // runtime: Atrium keeps running on plain Paper/Folia. compileOnly: nothing of it is bundled.
    compileOnly(project(":compat:sourbyapi-26.2"))
    testImplementation(project(":compat:sourbyapi-26.2"))
}
