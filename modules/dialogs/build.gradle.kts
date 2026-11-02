plugins {
    id("atrium.module")
}

description = "Admin-defined dialogs (Paper Dialog API) with validated inputs and safe actions"

dependencies {
    // Optional: Bedrock players get native forms through Floodgate/Geyser. Both are compile-time only; the
    // hook is instantiated only when the floodgate plugin is enabled (see FloodgateDialogs).
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT") { isTransitive = false }
    compileOnly("org.geysermc.cumulus:cumulus:1.1.2")
}
