# Staff inventory recovery

Staff mode saves the pre-staff inventory in SQL. Exiting staff mode now completes the return teleport
before applying saved items, XP and other state. Inventory application runs on the player's current
entity scheduler; the row is retained if teleport, entity scheduling or restore fails. Normal exit,
join recovery and reviewed `/staffmode recover <player> apply` share this ordering.

Each new snapshot has an immutable `snapshot_id`. State changes and finish match the player's UUID,
snapshot ID and creation stamp, so delayed work cannot delete a newer row for the same player.
Finish copies the exact database payload into `{prefix}staff_snapshot_history` and deletes its active
row in one transaction. Archives are recovery evidence, never automatic restore candidates. They
are retained without automatic pruning; deliberate staff discard also archives the discarded row.

Set `server-id` in `plugins/Atrium/modules/staff.yml` to a different stable name on every backend
sharing SQL, for example `lobby` and `island`. The default is `local` for a standalone installation.
The identity follows newly created snapshots. Changing it is an operational identity change:
existing snapshots with another origin are retained, not automatically rewritten or reassigned.
Restart after changing this identity.

Rows written before this migration have an empty origin and are retained for review. Do not delete
them to get rid of the warning. An ACTIVE foreign row can be restored by returning to its original
backend with the matching identity; an ambiguous legacy row needs operator review of its original
world/profile and database backup. Existing reviewed recovery commands apply only to RESTORING
rows; they are not an automatic import or cross-profile restore tool.

After verifying a legacy row's original world/profile/backend with `staffmode recover <player> list`,
the backend console can explicitly run `staffmode recover <player> adopt`. This accepts only a readable
row with a blank origin, and refuses a player known online locally or in the cached network roster.
It binds that exact row to this backend and assigns a token if absent. It does not apply, clear or
merge inventory. Roster absence is not an authoritative distributed offline guarantee: operators
must coordinate all backends before adopting. No adoption is automatic during upgrade.

For the first upgrade of backends sharing SQL, stop all old backends before starting the first new
one. Let its staff migrations finish, then start the remaining upgraded backends. Mixed old/new
versions are unsafe: the old version does not understand the new ownership and token checks.

This update does not create a distributed staff session or qualify profile switching during staff
mode. Do not manually replay an archive into a different player's profile. Missing saved worlds
now stop restoration instead of applying inventory in an unrelated world.

The diagnosed source defect was an inventory restore followed by an unawaited teleport and early
snapshot deletion. Live logs showed staff transitions on both network backends; they did not prove
which later subsystem overwrote the inventory. Previously deleted rows cannot be reconstructed
by this fix. Unit test sources cover stale-token refusal and archive retention. Compilation and JAR
assembly are separate from runtime inventory validation; no automated toggles were performed on
live player accounts.
