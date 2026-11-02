package dev.yanianz.atrium.vault;

/** Receives audit entries. Implementations must be thread-safe, non-blocking and never throw. */
@FunctionalInterface
public interface AuditSink {
    AuditSink NONE = entry -> { };

    void record(AuditEntry entry);
}
