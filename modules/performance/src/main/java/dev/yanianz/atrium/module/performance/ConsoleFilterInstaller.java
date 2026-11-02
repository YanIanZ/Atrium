package dev.yanianz.atrium.module.performance;

import java.util.function.Supplier;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.slf4j.Logger;

/**
 * Installs one log4j filter on the root logger. It never changes what the server logs by itself: it hides the
 * lines an admin configured and keeps the recent warnings and errors in {@link PluginErrorLog}.
 *
 * <p>log4j-core is provided by the server. When it is missing or a different build cannot be used, installation
 * fails softly with a warning and the console stays untouched - a filter is optional, a broken console is not.
 */
final class ConsoleFilterInstaller extends AbstractFilter {
    private static final long serialVersionUID = 1L;

    private final Supplier<LogRules> rules;
    private final PluginErrorLog recent;
    private final Logger logger;
    /** Root logger configuration this filter was added to; null when installation failed. */
    private LoggerConfig root;
    private volatile boolean installed;

    ConsoleFilterInstaller(Supplier<LogRules> rules, PluginErrorLog recent, Logger logger) {
        super(Result.NEUTRAL, Result.NEUTRAL);
        this.rules = rules;
        this.recent = recent;
        this.logger = logger;
    }

    /** @return true when the filter is active */
    boolean install() {
        if (installed) {
            return true;
        }
        try {
            org.apache.logging.log4j.spi.LoggerContext context = LogManager.getContext(false);
            if (!(context instanceof LoggerContext core)) {
                logger.warn("The console filter needs the log4j core context; the console is left unchanged");
                return false;
            }
            Configuration configuration = core.getConfiguration();
            LoggerConfig target = configuration.getRootLogger();
            target.addFilter(this);
            core.updateLoggers();
            root = target;
            installed = true;
            return true;
        } catch (LinkageError | RuntimeException e) {
            logger.warn("The console filter could not be installed ({}); the console is left unchanged",
                    e.toString());
            return false;
        }
    }

    void uninstall() {
        LoggerConfig target = root;
        root = null;
        installed = false;
        if (target == null) {
            return;
        }
        try {
            target.removeFilter(this);
            org.apache.logging.log4j.spi.LoggerContext context = LogManager.getContext(false);
            if (context instanceof LoggerContext core) {
                core.updateLoggers();
            }
        } catch (LinkageError | RuntimeException e) {
            logger.warn("The console filter could not be removed ({})", e.toString());
        }
    }

    @Override
    public Result filter(LogEvent event) {
        if (event == null) {
            return Result.NEUTRAL;
        }
        LogRules current = rules.get();
        if (!current.enabled()) {
            return Result.NEUTRAL;
        }
        String loggerName = event.getLoggerName();
        String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        boolean hasThrowable = event.getThrown() != null;
        String stack = hasThrowable ? stackOf(event.getThrown()) : null;
        LogRules.Decision decision = current.decide(loggerName, message, hasThrowable);
        if (event.getLevel().isMoreSpecificThan(Level.WARN) || hasThrowable) {
            String shown = decision.tagged() && !decision.tag().isEmpty()
                    ? decision.tag() + " " + message
                    : message;
            recent.add(System.currentTimeMillis(), event.getLevel().name(), loggerName, shown, stack);
        }
        return decision.hide() ? Result.DENY : Result.NEUTRAL;
    }

    private static String stackOf(Throwable error) {
        StringBuilder out = new StringBuilder();
        for (StackTraceElement element : error.getStackTrace()) {
            out.append("at ").append(element.getClassName()).append('.').append(element.getMethodName())
                    .append("\n");
            if (out.length() > 8192) {
                break;
            }
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return "AtriumConsoleFilter";
    }
}
