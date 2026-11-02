package dev.yanianz.atrium.module.proxy;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/proxy.yml */
@ConfigSerializable
public class ProxyConfig {
    @Comment("Name this server has in the proxy. Leave empty to ask the proxy with GetServer on first join.")
    public String serverName = "";

    @Comment("Shared HMAC secret for the atrium:<topic> envelope. Every server in the network must use the same\n"
            + "value. Leave empty and the module generates a random 32-byte secret on first start; copy it to\n"
            + "the other servers. The secret is never written to the log.")
    public String sharedSecret = "";

    @Comment("Accepted difference between the sender's clock and this server, in seconds (5-300).")
    public int trustWindowSeconds = 30;

    @Comment("How long the proxy server/player cache is refreshed, in seconds (2-300).")
    public int syncIntervalSeconds = 10;

    @Comment("Maximum number of outgoing publishes held while no player is online to carry them (1-4096).")
    public int queueMaxMessages = 128;

    @Comment("Seconds an outgoing publish may wait for a carrier player before it is dropped (5-3600).")
    public int queueTtlSeconds = 120;

    @Comment("Number of recent nonces kept for replay detection (256-65536).")
    public int replayCacheSize = 4096;

    @Comment("Seconds a nonce is remembered for replay detection (60-1800). The effective value is raised\n"
            + "to at least twice the trust window plus five seconds, so a replayed message cannot become\n"
            + "valid again after the nonce expires.")
    public int replayCacheSeconds = 120;

    @Comment("Minimum seconds between two rejection log lines. Rejections are counted even when not logged.")
    public int rejectLogSeconds = 5;
}
