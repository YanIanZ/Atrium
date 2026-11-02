# Contributing to Atrium

Atrium is a proprietary project (see [LICENSE](LICENSE)). The source is public for transparency and review, but
the project is not open to unsolicited code contributions.

## Issues are welcome

- **Bug reports:** open an [issue](https://github.com/YanIanZ/Atrium/issues) with the Atrium version
  (`/atrium version`), the server software and version (Paper or Folia, `/version`), the Java version, the list of
  other plugins, the steps to reproduce, and the relevant log lines (full stack traces, not screenshots).
- **Feature requests:** open an issue describing the use case, not only the desired command.
- **Security vulnerabilities:** do **not** open a public issue. Follow [SECURITY.md](SECURITY.md).

Remove passwords, webhook URLs, proxy secrets and IP addresses from configs and logs before you post them.

## Pull requests

Pull requests are accepted **by invitation only**. Unsolicited pull requests are closed without review. If you would
like to work on something, open an issue first and ask.

Feedback and suggestions submitted through issues may be used in Atrium without obligation (see LICENSE).

## Development workflow (for invited contributors)

Requirements: Java 25 and a POSIX shell. Gradle is provided by the wrapper.

```sh
./gradlew build                          # all modules, unit tests, Atrium jar
./gradlew :modules:<name>:build          # one module
./gradlew :atrium-vault:build            # Atrium Vault
./gradlew build -PsourbycraftRepo=/path/to/SourbyCraft   # offline: SourbyCraft API from a local clone
```

Runtime smoke tests boot a real server with the freshly built jar:

1. Put a Folia server jar at `run/folia/folia.jar`. `run/` is ignored by Git; `run/folia` is the shared baseline
   and is not used for tests directly.
2. Create an isolated test server once: `scripts/new-server.sh <name> <port>` (pick a free port, for example in the
   range 25601 to 25699). It is created in `run/<name>`.
3. Build and smoke-test:

   ```sh
   ./gradlew build && scripts/smoke.sh run/<name> "atrium modules" "<another console command>"
   ```

   The script copies the jar, boots the server, runs the console commands, stops the server and prints Atrium's log
   lines plus every warning and error. It must end with `SMOKE: OK`. Do not run two smoke tests on the same
   directory at the same time.

Console smoke tests do not replace in-game testing: test GUIs, entities and cross-server features with a real
client as well.

## Code rules

The architecture and the mandatory code rules (clean-room rule, Folia threading, untrusted text, money and item
handling, storage, events, tests) are in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). In short:

- Write original code. Do not copy code from other plugins, whether from source, decompilation or otherwise.
- Follow the structure of the reference module `modules/player/`.
- Ship `en_US` and `id_ID` texts with the same keys, use theme tags instead of raw colours, and give every command a
  description.
- Add JUnit tests for pure logic and a regression test for every fixed bug.
- Regenerate `docs/COMMANDS.md` and `docs/PERMISSIONS.md` with `/atrium docs` when commands or permissions change,
  and add an entry under `[Unreleased]` in [CHANGELOG.md](CHANGELOG.md).
