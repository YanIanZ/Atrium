package dev.yanianz.atrium.module.chat;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatRoomTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @Test
    void normalisesNames() {
        assertEquals(Optional.of("my-room_1"), ChatRoom.normaliseName(" My-Room_1 ", 24));
        assertTrue(ChatRoom.normaliseName("bad name", 24).isEmpty());
        assertTrue(ChatRoom.normaliseName("", 24).isEmpty());
        assertTrue(ChatRoom.normaliseName("toolongname", 4).isEmpty());
    }

    @Test
    void joinRulesRespectOwnerMembersInvitesAndLocks() {
        ChatRoom open = room(false);
        ChatRoom locked = room(true);

        assertTrue(open.canJoin(OWNER, false));
        assertTrue(open.canJoin(MEMBER, false));
        assertTrue(open.canJoin(OTHER, true));
        assertFalse(open.canJoin(OTHER, false));

        ChatRoom invited = locked.withInvited(OTHER);
        assertTrue(invited.canJoin(OTHER, false));
        assertFalse(locked.canJoin(OTHER, true));
    }

    @Test
    void membershipChangesKeepOtherState() {
        ChatRoom room = room(true);

        ChatRoom joined = room.withInvited(OTHER).withMember(OTHER);
        assertTrue(joined.isMember(OTHER));
        assertFalse(joined.invited().contains(OTHER));
        assertTrue(joined.locked());

        ChatRoom left = joined.withoutMember(OTHER);
        assertFalse(left.isMember(OTHER));
        assertTrue(left.locked());
    }

    private static ChatRoom room(boolean locked) {
        return new ChatRoom("test", OWNER, Set.of(OWNER, MEMBER), Set.of(), locked, 0);
    }
}
