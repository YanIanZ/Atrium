# Atrium documentation

| Document | Contents |
|---|---|
| [CONFIGURATION.md](CONFIGURATION.md) | `config.yml` keys (database, locale, theme), `modules.yml`, per-module files, lang files and theme tags, what `/atrium reload` applies |
| [COMMANDS.md](COMMANDS.md) | Every command with aliases, description and usage, grouped by module (generated) |
| [PERMISSIONS.md](PERMISSIONS.md) | Every permission with its default and description, grouped by module (generated) |
| [SOURBYCRAFT.md](SOURBYCRAFT.md) | SourbyCraft support: MultiProfile profile-scoped data, AWF worlds, economy with profile purses, region metrics |
| [ROADMAP.md](ROADMAP.md) | Development phases, what shipped in each, open items and known limits |

`COMMANDS.md` and `PERMISSIONS.md` are generated with `/atrium docs` from the default configuration. Run
`/atrium docs` on your own server to get the lists for your configuration (disabled modules, disabled commands and
custom aliases included); the files are written to `plugins/Atrium/docs/`.

Elsewhere in the repository:

- [README.md](../README.md): overview, install, integrations, Atrium Vault, releases, building.
- [CHANGELOG.md](../CHANGELOG.md): changes per release.
- [CONTRIBUTING.md](../CONTRIBUTING.md): issues, build and smoke-test workflow.
- [SECURITY.md](../SECURITY.md): reporting vulnerabilities.
- [DEVELOPMENT.md](DEVELOPMENT.md): architecture and code rules for contributors.
