# Chat moderation update

The chat module keeps its existing formats, private messages, staff chat, rooms, colours, emoji,
mentions and authenticated proxy relay. This update extends content moderation and corrects
reload/persistence feedback. It is an independent implementation; no ChatControl code or resources
are included.

## Content settings

In `plugins/Atrium/modules/chat.yml`:

```yaml
content:
  signs: false
  books: false
  anvils: false
anti-spam:
  similarity-window-seconds: -1
```

Content settings are opt-in and reloadable with `/atrium reload`. They use the existing `filter`
and `ads` rules, replacement text, global bypass permission and per-rule bypass groups. Enabling
a surface does not enable disabled word or advertisement rules.

Signs are checked per line; book pages and signed-book titles are checked per field. A BLOCK
rejects the entire edit. Accepted CENSOR changes are prepared before the event is updated, so a
blocked later field does not leave partially rewritten content. Replacement text is plain Adventure
text, not parsed MiniMessage. Unchanged components retain their styling. Rewritten components
discard prior interactions. Book author and unrelated item metadata are preserved. A replacement
title exceeding the vanilla 32-character limit rejects the signing operation.

Anvil previews are filtered without alerts/actions on each keystroke. Taking a result rechecks its
current name: a blocked or still-uncensored result is rejected without changing inputs, cursor or XP.
Preview rejection can leave an empty result slot. WARN rules can alert when a result is taken;
matches already removed by preview censorship do not generate a second alert/action.

These checks cover player edit events, not existing stored content, commands creating items or
all possible edits by other plugins. Split words across separate sign lines/book fields are not
combined into one rule match. Other plugins must honour cancellation and avoid overwriting a
filtered result after the final check. No Folia runtime compatibility claim follows from compilation.

## Text and spam rules

WORD rules and duplicate normalisation fold canonical accents: `café` and `café` match `cafe`.
Censor positions still refer to the original string, including combining accents. This is not
transliteration or Unicode-confusable matching. REGEX input keeps its prior accent behaviour.

`anti-spam.similarity-window-seconds` is `-1` by default, inheriting
`duplicate-window-seconds` for existing configurations. `0` disables similarity history; positive
values allow similarity detection independently of the exact-duplicate window. Similarity must
also be enabled. The existing bounded ten-message history and lexical edit-distance metric remain.

## Reload, persistence and remote messages

Reload keeps chat-delete message IDs and history. Reducing history size drops only oldest entries;
disable/restart still clears this in-memory cache. This is not a persistent chat audit log.

`/ignore`, `/msgtoggle`, `/socialspy`, `/staffchat` and `/rangespy` confirm their new state only after
the save completes. On failure the session state remains changed and a save failure is shown;
no durable success is claimed. Existing colour/tag saves use the same neutral failure message.

Remote private messages say **queued**, not delivered. There is no receiver acknowledgement,
and offline/ignore/PM-toggle policy at the destination can still prevent delivery. The existing
name-based remote target/reply protocol remains; this update does not claim UUID-bound remote
identity or complete ChatControl feature parity.

## Evidence and validation

Behavioural reference: the owner's local `/Volumes/Ext/REA/Chat/analysis/` reports for artifact
SHA-256 `c1618ef1a0d5be4ddcae2975f18a070a8ebcf94d3867247ac21b58338bdc05fd`.
REA MCP verified the artifact identity and ZIP inventory; inventory alone establishes no runtime
behaviour. A read-only OM/OpenCode DeepSeek Economy worker reviewed current Atrium source for
PM, preferences and reload consistency. Its findings were checked against source before changes.

Regression test sources cover accented censorship offsets, independent similarity windows,
component replacement boundaries and history resizing. Tests/server boot were not executed during
this update; Java and regression test source compilation and JAR assembly are the validation scope.
