# Security policy

## Supported versions

Only the **latest release** receives security fixes; a fix ships as the next patch version. Please reproduce a
problem on the latest release before reporting it.

| Version | Supported |
|---|---|
| Latest stable release | Yes |
| Older releases | No |

## Reporting a vulnerability

Report vulnerabilities **privately** through GitHub Security Advisories: open the repository's
[Security tab](https://github.com/YanIanZ/Atrium/security) and choose **Report a vulnerability**.

Do not open a public issue, pull request or discussion for a vulnerability, and do not post details on Discord or
other public channels before a fix is released.

Please include:

- the Atrium (and, if relevant, Atrium Vault) version, the server software and version, and the Java version;
- the affected module, command or feature;
- step-by-step reproduction, with the configuration needed (secrets removed);
- the impact you expect (for example "any player can duplicate items").

You will receive an acknowledgement after the report has been reviewed. Fixes are released as a new version and the
advisory is published after the release. Reporters are credited in the advisory unless they ask not to be.

## Scope

In scope:

- **Item or money duplication**, money creation or loss caused by Atrium (GUIs, sell, kits, economy, inventories,
  Atrium Vault).
- **Permission bypasses**: using a command or affecting another player without the required permission, escaping
  limits, cooldowns, costs or `commandrules`.
- **Injection**: player-controlled text being parsed as MiniMessage, PlaceholderAPI placeholders or commands; SQL
  injection; unsafe file paths.
- **Proxy messaging**: forging, replaying or tampering with cross-server messages (HMAC authentication), or acting
  on unauthenticated plugin messages.
- Data corruption or data exposure through Atrium's storage, importer or audit log.
- Crashes or unbounded resource use a regular player can trigger.

Out of scope:

- Problems that require operator access, console access or write access to the server files.
- Vulnerabilities in Paper, Folia, SourbyCraft or third-party plugins themselves (report them upstream).
- Misconfiguration, such as granting `atrium.*` to everyone or disabling Atrium Vault's guard.
- Modified builds, or builds that were not downloaded from the official releases page.
