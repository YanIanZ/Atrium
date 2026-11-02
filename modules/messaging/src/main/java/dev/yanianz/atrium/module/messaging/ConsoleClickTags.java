package dev.yanianz.atrium.module.messaging;

import dev.yanianz.atrium.core.scheduler.Scheduler;
import java.time.Duration;
import java.util.function.Consumer;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/**
 * {@code <cc:'/command'>text</cc>} and {@code <cci:'/command'>text</cci>} for admin messages. The enclosed text
 * gets a click action: {@code cc} runs the command as the console, {@code cci} only puts it in the player's chat
 * bar (suggest), which is safe for everyone.
 *
 * <p>Only admin-written templates are ever parsed with these tags. {@code cc} additionally requires
 * {@code atrium.messaging.ctellraw.console}; without it the tag renders its text with no click action and a
 * warning is logged once, so a misconfigured permission cannot silently hand out console access.
 */
final class ConsoleClickTags {
    private final Scheduler scheduler;
    private final boolean consoleAllowed;
    private final Consumer<String> warning;

    ConsoleClickTags(Scheduler scheduler, boolean consoleAllowed, Consumer<String> warning) {
        this.scheduler = scheduler;
        this.consoleAllowed = consoleAllowed;
        this.warning = warning;
    }

    TagResolver resolver() {
        TagResolver console = TagResolver.resolver("cc", (queue, ctx) -> {
            String command = command(queue.popOr("<cc> needs a command").value());
            if (command == null || !consoleAllowed) {
                if (command != null) {
                    warning.accept("A message used <cc> without atrium.messaging.ctellraw.console; "
                            + "the console click was removed.");
                }
                return Tag.styling(builder -> {});
            }
            return Tag.styling(builder -> builder.clickEvent(consoleClick(command)));
        });
        TagResolver suggest = TagResolver.resolver("cci", (queue, ctx) -> {
            String command = command(queue.popOr("<cci> needs a command").value());
            if (command == null) {
                return Tag.styling(builder -> {});
            }
            return Tag.styling(builder -> builder.clickEvent(ClickEvent.suggestCommand("/" + command)));
        });
        return TagResolver.resolver(console, suggest);
    }

    /** Normalises a command: no leading slash, no newlines, capped length. {@code null} when empty. */
    static String command(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        value = value.replaceAll("[\\r\\n\\t]", " ").strip();
        if (value.isEmpty() || value.length() > 256) {
            return null;
        }
        return value;
    }

    private ClickEvent consoleClick(String command) {
        return ClickEvent.callback(audience -> scheduler.global(() -> {
            CommandSender console = Bukkit.getConsoleSender();
            Bukkit.dispatchCommand(console, command);
        }), ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build());
    }
}
