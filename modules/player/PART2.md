# Player utilities, part 2

- Portable menus use Paper MenuType with reachability disabled.
- `/clear [player] [item] [amount]`: amount is a positive integer or `all`;
  item is a material key or `all`. Omit player for self, or use your name
  to supply item/amount. Clearing someone else requires repeating the exact
  command within the configured confirmation window. Confirmation binds to UUID,
  item and amount; changed arguments start a new confirmation.
- Inventory viewers refresh from the owner's thread. Display stacks are never
  written back. Plain left-click swaps the current live slot with the viewer's
  cursor, checking modification permission on every click. Bottom-inventory
  left-clicks prepare the cursor. Other clicks and all drags are blocked.
  On Folia, editing requires both players to share the executing region;
  distant viewing remains read-only. No shared live container crosses regions.
  Slots 0–35 are storage, 36–39 armor, 40 offhand.
- Lore line numbers are one-based. Text is literal unless
  `atrium.itemname.color` is granted; only color and decoration tags resolve.
- No persisted state or migrations are needed.

Validation: module build, full build, then isolated run/player smoke.
Interactive follow-up: two players in the same/different regions, read-only
shift-click/drag/number-key/creative paths, permission revocation, disconnect,
concurrent target inventory changes, portable menu recipes and skull resolution.
