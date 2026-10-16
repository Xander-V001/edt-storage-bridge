# EDT Storage Bridge

Independently developed extension for **1C:EDT 2026.2** and classic 1C configuration repositories.

## Status

- Previous build #38938763356 passed against EDT 2026.2 API (Tycho/Java 25, 3 core tests).
- The project settings UI was compiled in the successful manual CI build #38944234655 but **has not been installed/smoke-tested in EDT**.
- A new independent read-only report transport and decoding tests have been added **after that build** and are uncompiled. No real repository operations are wired into the UI.
- Working branch `feature/edt-2026.2-integration`; PR #6 remains draft until approved build and smoke test.

## Planned user flow

Right-click a project in EDT Navigator → **Работа с хранилищем**. The per-project dialog lets you save:

- repository address, user and securely encrypted password;
- **optional** already-connected 1C infobase (file directory or server/infobase name);
- **optional** path to the installed platform executable (`1cv8.exe`);
- observed remote revision and verified locally applied revision (both unknown until real verification).

Only settings persist. Entering them never launches Designer, connects to the base or changes data.

## Integration strategy

Prefer platform-supported operations through an existing connected infobase when needed. Direct supported read-only access remains a candidate for repository history and version queries. Before any receive or commit, inspect locks, identity and divergence and require explicit consent to modify the base. Keep Git and EDT project revisions separate from the classical repository.

[UI details](docs/STORAGE_UI.md) · [Architecture](docs/ARCHITECTURE.md) · [ADR 0003](docs/decisions/0003-optional-infobase.md) · [Roadmap](docs/ROADMAP.md) · [Rules](AGENTS.md)

**Builds only on explicit manual request**, not per commit. Do not use user VPS or real infobases/repositories without approval.
