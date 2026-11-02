package dev.yanianz.atrium.module.localeeditor;

import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Turns an asynchronous failure of the locale editor into one translated line, and logs only what is actually a
 * server problem. A file that is not valid YAML or a key that holds a section is a user error: it is reported with a
 * precise message instead of a generic "something went wrong".
 */
final class LocaleReport {
    private LocaleReport() {}

    static void report(ModuleContext ctx, CommandSender sender, Throwable error, String operation, String key) {
        Throwable cause = unwrap(error);
        if (cause instanceof LangDocument.InvalidDocumentException invalid) {
            ctx.logger().warn("{} failed: {}", operation, invalid.getMessage());
            send(ctx, sender, messageKey(invalid.reason()), Msg.unparsed("key", key));
            return;
        }
        ctx.logger().error("{} failed", operation, cause);
        send(ctx, sender, "localeeditor.failed");
    }

    /** The lang key of a user error. It already carries the module prefix: callers must not add another one. */
    static String messageKey(LangDocument.InvalidDocumentException.Reason reason) {
        return switch (reason) {
            case SECTION -> "localeeditor.section-key";
            case VERIFY -> "localeeditor.verify-failed";
            case PARSE -> "localeeditor.parse-failed";
        };
    }

    /** Sends on the recipient's own thread; commands and file callbacks both reach this off-thread. */
    static void send(ModuleContext ctx, CommandSender recipient, String key,
            net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... tags) {
        Runnable work = () -> ctx.messages().send(recipient, key, tags);
        if (recipient instanceof Player player) {
            ctx.scheduler().entity(player, work, null);
        } else {
            ctx.scheduler().global(work);
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause == null ? error : cause;
    }
}
