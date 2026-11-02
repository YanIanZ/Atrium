package dev.yanianz.atrium.module.discord;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A validated Discord webhook URL.
 *
 * <p>The token is the credential: it is kept in {@link #uri()} only and must never be logged. {@link #masked()}
 * exposes the last four digits of the numeric webhook id, which is safe to show to staff.
 */
public record WebhookTarget(URI uri, String id, String masked) {
    public static final Set<String> ALLOWED_HOSTS = Set.of("discord.com", "discordapp.com", "canary.discord.com");

    private static final String PATH_PREFIX = "/api/webhooks/";
    private static final Pattern ID = Pattern.compile("[0-9]{1,25}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._-]{1,200}");

    /** Parses and canonicalises a webhook URL. Leading and trailing whitespace is ignored. */
    public static WebhookTarget parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidWebhookException("URL is empty");
        }
        URI parsed;
        try {
            parsed = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new InvalidWebhookException("malformed URL");
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme())) {
            throw new InvalidWebhookException("scheme must be https");
        }
        if (parsed.getRawUserInfo() != null) {
            throw new InvalidWebhookException("user information is not allowed");
        }
        if (parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw new InvalidWebhookException("query and fragment are not allowed");
        }
        int port = parsed.getPort();
        if (port != -1 && port != 443) {
            throw new InvalidWebhookException("only the default HTTPS port is allowed");
        }
        String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidWebhookException("missing host");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (!ALLOWED_HOSTS.contains(host)) {
            throw new InvalidWebhookException("host must be discord.com, discordapp.com or canary.discord.com");
        }
        String path = parsed.getRawPath();
        if (path == null || !path.startsWith(PATH_PREFIX)) {
            throw new InvalidWebhookException("path must start with " + PATH_PREFIX);
        }
        String rest = path.substring(PATH_PREFIX.length());
        if (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        String[] parts = rest.split("/", -1);
        if (parts.length != 2) {
            throw new InvalidWebhookException("path must be /api/webhooks/<id>/<token>");
        }
        String id = parts[0];
        String token = parts[1];
        if (!ID.matcher(id).matches()) {
            throw new InvalidWebhookException("webhook id must be numeric");
        }
        if (!TOKEN.matcher(token).matches()) {
            throw new InvalidWebhookException("webhook token contains unsupported characters");
        }
        if (".".equals(token) || "..".equals(token)) {
            throw new InvalidWebhookException("webhook token is not a valid path segment");
        }
        // Rebuild from validated parts so percent-encoding and case can never change what we send.
        URI canonical = URI.create("https://" + host + PATH_PREFIX + id + "/" + token);
        return new WebhookTarget(canonical, id, mask(id));
    }

    /** {@code webhook#<last 4 characters of the id>}; never contains the token. */
    public static String mask(String id) {
        String tail = id.length() <= 4 ? id : id.substring(id.length() - 4);
        return "webhook#" + tail;
    }

    /** Masked so an accidental log or exception trace cannot expose the token. */
    @Override
    public String toString() {
        return "WebhookTarget[" + masked + "]";
    }

    /** Failure reason. Never includes the URL or the token. */
    public static final class InvalidWebhookException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        InvalidWebhookException(String message) {
            super(message);
        }
    }
}
