package dev.yanianz.atrium.module.inspect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Groups online players by the address of their current connection; pure and unit tested. */
final class SameIp {
    record Session(UUID uuid, String name, String address) {}

    record Group(String address, List<Session> sessions) {
        Group {
            sessions = List.copyOf(sessions);
        }
    }

    private SameIp() {}

    /**
     * Groups with at least two accounts, largest first. Loopback addresses and blank/unknown addresses are
     * ignored: every local test client shares 127.0.0.1 and that is not evidence of alt accounts.
     */
    static List<Group> group(Collection<Session> sessions) {
        Map<String, List<Session>> byAddress = new LinkedHashMap<>();
        for (Session session : sessions) {
            if (session.address() == null || session.address().isBlank()) {
                continue;
            }
            String address = session.address().strip().toLowerCase(Locale.ROOT);
            if (isLoopback(address)) {
                continue;
            }
            byAddress.computeIfAbsent(address, key -> new ArrayList<>()).add(session);
        }
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<Session>> entry : byAddress.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            List<Session> members = new ArrayList<>(entry.getValue());
            members.sort(Comparator.comparing(Session::name, String.CASE_INSENSITIVE_ORDER));
            groups.add(new Group(entry.getKey(), members));
        }
        groups.sort(Comparator.comparingInt((Group group) -> group.sessions().size()).reversed()
                .thenComparing(Group::address));
        return List.copyOf(groups);
    }

    static boolean isLoopback(String address) {
        if (address == null) {
            return false;
        }
        String value = address.strip();
        return value.equals("127.0.0.1") || value.equals("0:0:0:0:0:0:0:1") || value.equals("::1")
                || value.startsWith("127.");
    }
}
