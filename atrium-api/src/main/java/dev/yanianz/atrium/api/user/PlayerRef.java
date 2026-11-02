package dev.yanianz.atrium.api.user;

import java.util.UUID;

/** A player identity (uuid + name) that may not be loaded, e.g. a player who never joined. */
public record PlayerRef(UUID uuid, String name) {}
