# Next development step: verified EDT integration (without building)

**Decision (2026-10-16):** perform a read-only compatibility spike for 1C:EDT before implementing any actual configuration repository update/commit logic.

## Why

The current prototype compiles against public Eclipse 2024-06 APIs. This does **not** prove its compatibility with a concrete 1C:EDT distribution. Guessing a private API or depending on undocumented internals would be a fragile foundation.

## Deliverables

1. Inventory the official EDT p2 target, Java version, documented public APIs for project selection, import/export and update operations.
2. Select and pin exact primary EDT build (2026.1.x proposed, confirm installed build during smoke test).
3. Define a repository-adapter contract that is separate from UI, Git and EDT project model.
4. Document a **read-only** connection/status probe using supported 1C platform commands, preserving 1C process exit code, stdout/stderr and timeout handling, with secrets redacted.
5. Prepare a minimal test matrix for version mismatch, no project selected, unavailable connection, missing permissions and partial/interrupted operations.
6. Use an existing successful GitHub Actions p2 archive for a manual, **read-only** EDT installation smoke test; only the user explicitly approves subsequent builds.

## Acceptance criteria

- Public APIs and version matrix grounded in actual reference materials (links + concrete bundle IDs).
- No speculative classes from unavailable EDT bundles.
- No writes to configuration repositories or production databases.
- No GitHub Actions runs triggered by commits and PRs.
- Any new source code remains marked *unverified* until an explicitly requested build.

The following tasks remain separate: [#2](https://github.com/Xander-V001/edt-storage-bridge/issues/2) verified EDT target, [#3](https://github.com/Xander-V001/edt-storage-bridge/issues/3) read-only repository discovery.
