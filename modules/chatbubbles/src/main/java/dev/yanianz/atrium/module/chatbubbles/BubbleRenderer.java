package dev.yanianz.atrium.module.chatbubbles;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** One bubble backend. Implementations are selected once at enable/reload and torn down on disable. */
interface BubbleRenderer {
    /** Shows a bubble for the speaker. Called on the speaker's own region thread. */
    void show(Player speaker, Component text, ChatBubblesConfig cfg);

    /** Ends the speaker's bubble, if any. Safe from any thread. */
    void remove(UUID speaker);

    /** Ends every bubble and releases backend resources. */
    void shutdown();
}
