package dev.yanianz.atrium.module.moderation;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * IP address literals and CIDR ranges, normalised so that equal addresses always compare equal as text:
 * <ul>
 *   <li>IPv4: four decimal octets without leading zeros ({@code 10.0.0.1}).</li>
 *   <li>IPv6: RFC 5952 (lower case, longest zero run compressed): {@code 2001:DB8:0:0::1} becomes
 *       {@code 2001:db8::1}. Brackets and zone ids ({@code %eth0}) are removed.</li>
 *   <li>IPv4-mapped IPv6 ({@code ::ffff:1.2.3.4}) becomes the IPv4 address, because that is how the same
 *       client appears on a dual-stack socket.</li>
 *   <li>Ranges: {@code address/prefix} with the host bits cleared ({@code 10.1.2.3/8} becomes {@code 10.0.0.0/8}).</li>
 * </ul>
 * Never performs DNS lookups: host names are rejected.
 */
final class IpAddresses {
    private IpAddresses() {}

    /** Normalised single address, or empty when {@code input} is not an IP literal. */
    static Optional<String> normalize(String input) {
        return parse(input).map(IpAddresses::format);
    }

    static Optional<String> normalize(InetAddress address) {
        return Optional.of(format(canonical(address.getAddress())));
    }

    /** Normalised address or CIDR range, or empty when invalid. A full-length prefix yields a plain address. */
    static Optional<String> normalizeRange(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String text = input.trim();
        int slash = text.indexOf('/');
        if (slash < 0) {
            return normalize(text);
        }
        Optional<byte[]> base = parseRaw(text.substring(0, slash));
        int prefix = parsePrefix(text.substring(slash + 1));
        if (base.isEmpty() || prefix < 0) {
            return Optional.empty();
        }
        byte[] bytes = base.get();
        if (bytes.length == 16 && isMapped(bytes)) {
            if (prefix < 96) {
                return Optional.empty();
            }
            bytes = Arrays.copyOfRange(bytes, 12, 16);
            prefix -= 96;
        }
        int bits = bytes.length * 8;
        if (prefix > bits) {
            return Optional.empty();
        }
        byte[] network = applyMask(bytes, prefix);
        return Optional.of(prefix == bits ? format(network) : format(network) + "/" + prefix);
    }

    static boolean isAddressOrRange(String input) {
        return normalizeRange(input).isPresent();
    }

    /** Whether {@code address} equals {@code addressOrRange} or lies in the range. Different families never match. */
    static boolean matches(String addressOrRange, String address) {
        Optional<byte[]> target = parse(address);
        if (target.isEmpty()) {
            return false;
        }
        Optional<String> normalized = normalizeRange(addressOrRange);
        if (normalized.isEmpty()) {
            return false;
        }
        String range = normalized.get();
        int slash = range.indexOf('/');
        byte[] network = parse(slash < 0 ? range : range.substring(0, slash)).orElseThrow();
        byte[] candidate = target.get();
        if (network.length != candidate.length) {
            return false;
        }
        int prefix = slash < 0 ? network.length * 8 : Integer.parseInt(range.substring(slash + 1));
        return Arrays.equals(applyMask(candidate, prefix), network);
    }

    /** Hides the host part for public announcements: {@code 1.2.*.*}, {@code 2001:db8:*}. */
    static String mask(String addressOrRange) {
        String text = normalizeRange(addressOrRange).orElse(addressOrRange);
        int slash = text.indexOf('/');
        String address = slash < 0 ? text : text.substring(0, slash);
        if (address.indexOf(':') < 0) {
            String[] parts = address.split("\\.");
            return parts.length == 4 ? parts[0] + "." + parts[1] + ".*.*" : "*";
        }
        Optional<byte[]> bytes = parse(address);
        if (bytes.isEmpty()) {
            return "*";
        }
        byte[] b = bytes.get();
        return String.format(Locale.ROOT, "%x:%x:*", group(b, 0), group(b, 1));
    }

    // ---- internals ----

    private static Optional<byte[]> parse(String input) {
        return parseRaw(input).map(IpAddresses::canonical);
    }

    /** Parses without converting IPv4-mapped IPv6 to IPv4. */
    private static Optional<byte[]> parseRaw(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String text = input.trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        int zone = text.indexOf('%');
        if (zone >= 0) {
            text = text.substring(0, zone);
        }
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (text.indexOf(':') < 0) {
            return parseIpv4(text);
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = c == ':' || c == '.' || Character.digit(c, 16) >= 0;
            if (!ok) {
                return Optional.empty();
            }
        }
        if (text.indexOf('.') >= 0) {
            // Embedded IPv4 tail must itself be strict dotted decimal.
            String tail = text.substring(text.lastIndexOf(':') + 1);
            if (parseIpv4(tail).isEmpty()) {
                return Optional.empty();
            }
        }
        try {
            // ofLiteral (Java 22+) never resolves host names.
            InetAddress address = Inet6Address.ofLiteral(text);
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) {
                // Returned for IPv4-mapped literals: keep the mapped form so ranges can be converted.
                byte[] mapped = new byte[16];
                mapped[10] = (byte) 0xff;
                mapped[11] = (byte) 0xff;
                System.arraycopy(bytes, 0, mapped, 12, 4);
                return Optional.of(mapped);
            }
            return Optional.of(bytes);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<byte[]> parseIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return Optional.empty();
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) {
                return Optional.empty();
            }
            for (int j = 0; j < part.length(); j++) {
                if (part.charAt(j) < '0' || part.charAt(j) > '9') {
                    return Optional.empty();
                }
            }
            int value = Integer.parseInt(part);
            if (value > 255) {
                return Optional.empty();
            }
            bytes[i] = (byte) value;
        }
        return Optional.of(bytes);
    }

    private static int parsePrefix(String text) {
        if (text.isEmpty() || text.length() > 3) {
            return -1;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return -1;
            }
        }
        return Integer.parseInt(text);
    }

    private static boolean isMapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }

    private static byte[] canonical(byte[] bytes) {
        return bytes.length == 16 && isMapped(bytes) ? Arrays.copyOfRange(bytes, 12, 16) : bytes;
    }

    private static byte[] applyMask(byte[] bytes, int prefix) {
        byte[] out = bytes.clone();
        for (int i = 0; i < out.length; i++) {
            int keep = Math.max(0, Math.min(8, prefix - i * 8));
            int mask = keep == 0 ? 0 : (0xff << (8 - keep)) & 0xff;
            out[i] = (byte) (out[i] & mask);
        }
        return out;
    }

    private static int group(byte[] b, int index) {
        return ((b[index * 2] & 0xff) << 8) | (b[index * 2 + 1] & 0xff);
    }

    private static String format(byte[] b) {
        if (b.length == 4) {
            return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + "." + (b[3] & 0xff);
        }
        // RFC 5952: compress the longest run (>= 2) of zero groups, the first one on ties.
        int bestStart = -1;
        int bestLength = 0;
        for (int i = 0; i < 8; ) {
            if (group(b, i) != 0) {
                i++;
                continue;
            }
            int start = i;
            while (i < 8 && group(b, i) == 0) {
                i++;
            }
            if (i - start > bestLength) {
                bestStart = start;
                bestLength = i - start;
            }
        }
        if (bestLength < 2) {
            bestStart = -1;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                out.append("::");
                i += bestLength - 1;
                continue;
            }
            if (!out.isEmpty() && out.charAt(out.length() - 1) != ':') {
                out.append(':');
            }
            out.append(Integer.toHexString(group(b, i)));
        }
        return out.toString();
    }
}
