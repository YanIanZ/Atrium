plugins {
    id("atrium.module")
}

description = "Floating chat bubbles (client-side packets or a server TextDisplay) above players' heads."

dependencies {
    // Optional zero-lag renderer: a client-side TextDisplay mounted on the speaker. The packetevents
    // plugin provides these classes at runtime; the API jar is never bundled.
    compileOnly("com.github.retrooper:packetevents-api:2.14.0")
}
