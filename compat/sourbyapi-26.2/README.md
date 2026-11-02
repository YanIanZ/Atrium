# sourbyapi-26.2 (compile-only baseline)

Compile-only stand-in for the official SourbyCraft API (GitHub `YanIanZ/SourbyCraft`, branch `26.2`).

The API sources belong to the SourbyCraft project and are licensed GPL-3.0-only, so this repository does **not**
contain them. `sources.lock` pins a commit and the SHA-256 of every file. At build time the `fetchSourbyApi` task
downloads exactly those files from `raw.githubusercontent.com` at that commit, verifies every hash, and compiles them
against paper-api. The output is used `compileOnly` by `modules/sourbycraft` and is never bundled into any Atrium jar.

| Field | Value |
|---|---|
| Pinned commit | see the `commit` line in `sources.lock` (currently `63f1c3be`, Build 50) |
| Excluded from compilation | `api/simd/VectorMapPalette.java` (needs SourbyCraft's patched paper-api; unused by Atrium) |

`modules/sourbycraft` compiles its baseline code against this project, so `./gradlew build` fails as soon as Atrium
uses an API member the official branch does not have. Newer members that only exist in later SourbyCraft builds are
detected at runtime and are optional (see `docs/SOURBYCRAFT.md`).

## Offline builds
```sh
./gradlew build -PsourbycraftRepo=/path/to/SourbyCraft   # reads the pinned files with `git show` instead
```

## Updating the pin
```sh
git -C /path/to/SourbyCraft fetch origin
compat/sourbyapi-26.2/sync.sh /path/to/SourbyCraft origin/26.2   # rewrites sources.lock, read-only on the clone
./gradlew build
```
