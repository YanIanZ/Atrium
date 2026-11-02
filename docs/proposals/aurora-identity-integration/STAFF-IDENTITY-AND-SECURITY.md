# Atrium Identity Inspector, Moderation and Privacy

**Status:** proposed UX and policy, not shipped.

## Staff inspector

Request: `/whois <character-name>` or optional `/atrium identity inspect`. Resolve canonical name via authoritative AIE, then show character ID, displayed name, profile type, active node/status (if authorized), authenticated account name/UUID (elevated permission), other linked characters (elevated permission), name history (elevated permission) and moderation history (role-gated).

Do not trust a name-only lookup if it resolves ambiguously or stale. UI displays stable IDs with copy control; actions execute against the resolved immutable target and verify its revision again on submit.

## Scope of sanctions

| Action | Target |
|---|---|
| network account ban, IP ban, account security restrictions | authenticated account UUID (IP separately) |
| character jail/mute/restriction | immutable profile UUID if policy allows |
| per-world punishment / membership | profile UUID + world ID |
| temp restriction | server-side time + stable IDs + audit |

Renaming or switching characters must never bypass an account-wide sanction. Privilege escalation by reserving moderator names is prohibited; reserve staff/system prefixes and protect names from impersonation.

## Proposed permissions (do not treat as current)

- `atrium.identity.view-character` — non-sensitive character info
- `atrium.identity.inspect-account` — original account identity
- `atrium.identity.view-linked` — linked characters and name history
- `atrium.identity.staff-action` — approved moderation
- `atrium.identity.recover` — audited recovery workflow
- `atrium.identity.rename.override` — controlled override with reason

Avoid permissions that allow exposing all account UUIDs through public chat placeholders. Identity mappings should not be accessible through ordinary PlaceholderAPI expansions unless explicitly permission-aware.

## Staff audit requirements

Log at least: actor account UUID, staff role/permission context, target account/profile UUID, reason code, operation, previous/next revision, outcome, audit correlation ID and UTC timestamp. Separate sensitive log retention and access from gameplay logs; redact session secrets, IP if not necessary and full player inventory. Cross-server audit forwards authenticated stable IDs, not display-only names.

## Online/offline state

A staff action against an offline profile must be versioned and persist in an authoritative profile-specific repository. It must not be silently overwritten when the old player snapshot is restored. Show conflict/recovery rather than applying an uncertain mutation.

For current moderation records, run a migration that preserves legacy account UUID sanctions. No new profile-based enforcement may overwrite or re-key account sanctions.

## Abuse controls

Rate limit name create/rename and staff lookup. Distinct user-facing messages for invalid name, name taken, restricted, unavailable service, insufficient permissions and recovery. Avoid exposing whether a specific private account exists to unprivileged callers. All debug endpoints are admin-only; all account mapping exports require owner approval.

## Owner qualification

Test account ban across A->B->A, offline rename, duplicate-name check, staff fake name, expired permission, compromised proxy forwarding, cross-node conflicting lookup and audit retention/redaction. Real Java and Bedrock form controls tested separately.
