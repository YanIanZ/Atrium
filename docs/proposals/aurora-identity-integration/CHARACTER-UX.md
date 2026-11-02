# Character Experience — Native Dialogs, Onboarding and Selection

**Future-state UX spec; not implemented.** Paper's Dialog API is available in modern Minecraft Java clients; this is not arbitrary HTML UI. Reference: https://docs.papermc.io/paper/dev/dialogs/ . Bedrock forms via Floodgate require separate client testing and authorization.

## First join (account UUID verified)

`AUTHENTICATED -> PROFILE_CHECK -> FIRST_JOIN_DIALOG -> NAME_RESERVATION -> PROFILE_CREATE -> WORLD_PREPARE -> READY -> PLAY`.

A player with no profile is held in a restricted onboarding flow. Never treat a Redis timeout/profile-store failure as "no profile": such an error must show recovery/error UI and block creation until resolved. Prevent item drops, block interaction, teleport, commands and unsolicited plugin rewards during this state.

1. **Welcome**: explain account UUID retained, character-name is network-scoped, profiles may have separate inventory/economy.
2. **Text input**: character username 3–16 ASCII letters/digits/underscore. Display validation errors immediately; server checks canonical case-insensitive uniqueness, reserved/staff name policy and rate limits.
3. **Gamemode**: options configured by network (e.g. SkyBlock/Survival/RPG). Do not trust client-selected game mode without server policy/slot validation.
4. **Review and confirm**: show name, gamemode, initial slot use and data-scope policy.
5. **Persist**: submit one idempotent authoritative create operation; return distinct errors NAME_TAKEN, SLOT_LIMIT, RESERVED_NAME, IO_FAILURE, RECOVERY_REQUIRED, INVALID_REQUEST, RATE_LIMITED.
6. **Enter world**: wait for REQUIRED data adapters, source of truth and AWF world ready before opening gameplay. Apply GameProfile presentation only after commit.

Keep the Dialog visible or provide a retry path on expected failures; avoid creating duplicate profiles on repeated click or reconnect. Use one operation ID per confirmed action. Show clear progress text without inventing a completed state.

## Returning player /profile

A native multi-action selector shows character name, gamemode, slot, profile status (ready/locked/recovery) and last-played metadata **only if authoritative**. Actions: Play, Create, Rename, Cancel, Delete (requires a separate irreversible confirmation and retention policy), Recover (staff or guided). Default to the last active profile only when safe.

Switch invokes the authoritative transition and waits for commit. The GUI cannot directly write inventory or economy; it only sends intent. If player disconnects during switch, next join runs journal reconciliation, not blind re-creation.

## Identity presentation

- Public chat/tab/nameplate: character display and optional configured account indicator for staff.
- Account auth UUID never overwritten. GameProfile presentation owned by SourbyCraft; Atrium nick is a *cosmetic alias*, not the canonical character registry.
- Public player list, friend requests, chat /msg should resolve active characters; moderation and sanctions always resolve IDs.
- Consider language `id_ID` and `en_US` messages, accessible contrast, keyboard usage, actionable errors, input length and predictable dialog close behavior.
- Skin selection is profile preference with trusted texture source; an untrusted arbitrary username is not an authorization to load that person's authenticated identity.

## Proposed screens

| Screen ID | Trigger | Required controls |
|---|---|---|
| CH-01 | account with zero profiles | welcome / continue |
| CH-02 | create | name text input; gamemode selection; submit |
| CH-03 | name conflict | error, propose retry; no false availability guarantee |
| CH-04 | confirm | summary, confirm/cancel |
| CH-05 | progress | operation ID bound, cancel behavior defined |
| CH-06 | selector | character list with states and select |
| CH-07 | recovery | explicit retry/status, contact staff without information leak |
| CH-08 | rename | new name, availability check, confirmation and cooldown |
| CH-09 | staff inspector | permission-sensitive identity details (separate doc) |

## Events and permissions

Future Atrium listeners use `AuroraIdentityService` immutable snapshot and event context; do not assume events execute on Folia global thread. Suggested command UX: `/profile`, `/profile create`, `/profile select`, `/profile rename`; exact commands/permissions are **proposals**, not existing defaults. Existing `/atrium profile` remains supported until a versioned migration.

## Tests

Client: modern Java (native dialogs), Java reconnect while dialog open, wrong version fallback, invalid text, duplicate submit, backend timeout, missing SourbyAPI, profile switch during death or teleport, signed chat/tab/skin after presentation, Bedrock/Geyser if enabled. Security: forged custom click, bypassing Dialog flow via command, server-validated gamemode, rate limiting.
