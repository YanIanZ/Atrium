package dev.yanianz.atrium.module.chat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatTagsTest {
    private static ChatTags.Definition tag(String id, String permission) {
        return new ChatTags.Definition(id, "[" + id + "] ", "", permission);
    }

    @Test
    void resolvesOnlyTheSelectedTagWhenItIsAllowed() {
        List<ChatTags.Definition> tags = List.of(tag("vip", "atrium.chat.tag.vip"),
                tag("staff", "atrium.chat.tag.staff"));

        assertEquals("staff", ChatTags.resolve(tags, "staff", permission -> true).orElseThrow().id());
        assertTrue(ChatTags.resolve(tags, "missing", permission -> true).isEmpty());
        assertTrue(ChatTags.resolve(tags, "vip", "atrium.chat.tag.vip"::equals).isPresent());
    }

    @Test
    void deniedPermissionHidesTheSelectedTag() {
        List<ChatTags.Definition> tags = List.of(tag("vip", "atrium.chat.tag.vip"));
        assertTrue(ChatTags.resolve(tags, "vip", permission -> false).isEmpty());
    }

    @Test
    void compileKeepsTheFirstDuplicateIdAndWarns() {
        ChatConfig.Tags config = new ChatConfig.Tags();
        config.entries = new ArrayList<>(List.of(entry("vip", "atrium.chat.tag.vip"),
                entry("vip", "atrium.chat.tag.other")));
        List<String> warnings = new ArrayList<>();

        List<ChatTags.Definition> tags = ChatTags.compile(config, warnings::add);

        assertEquals(1, tags.size());
        assertEquals("atrium.chat.tag.vip", tags.getFirst().permission());
        assertEquals(1, warnings.size());
    }

    @Test
    void emptyPermissionIsAlwaysAllowed() {
        ChatTags.Definition open = tag("open", "");
        assertTrue(ChatTags.allowed(open, permission -> false));
    }

    private static ChatConfig.TagEntry entry(String id, String permission) {
        ChatConfig.TagEntry entry = new ChatConfig.TagEntry();
        entry.id = id;
        entry.prefix = "[" + id + "] ";
        entry.permission = permission;
        return entry;
    }
}
