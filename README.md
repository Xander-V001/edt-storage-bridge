# EDT Storage Bridge

Independently developed extension for **1C:EDT 2026.2** and classic 1C configuration repositories.

## Status

- Previous build #38938763356 passed against EDT 2026.2 API (Tycho/Java 25, 3 core tests).
- The project settings UI was compiled in the successful manual CI build #38944234655 but **has not been installed/smoke-tested in EDT**.
- New read-only history and **two-stage receive command** have been added **after that build**. These changes are UNCOMPILED and have not been tested in EDT.
- Receive now delegates **actual compare/merge UI to EDT's public `InfobaseUpdateDialogBasedCallback`** rather than a custom conflict resolver. It verifies exact selected EDT IB identity against /F or /S settings and requires existing sync baseline and clean Git branch. **Test mode, not validated; never try it on a valuable base before isolated tests.**
- Working branch `feature/edt-2026.2-integration`; PR #6 remains draft until approved build and smoke test.

## Planned user flow

Right-click a project in EDT Navigator → **Работа с хранилищем**. The per-project dialog lets you save:

- repository address, user and securely encrypted password;
- **optional** already-connected 1C infobase (file directory or server/infobase name);
- **optional** path to the installed platform executable (`1cv8.exe`);
- observed remote revision and verified locally applied revision (both unknown until real verification).

Saving settings alone never launches Designer or changes data. A separate explicit **Receive changes (test mode)** action can mutate the configured connected base after user confirmation; NOT yet approved for working infobases.

## Integration strategy

Prefer platform-supported operations through an existing connected infobase when needed. Direct supported read-only access remains a candidate for repository history and version queries. Before any receive or commit, inspect locks, identity and divergence and require explicit consent to modify the base. Local Git is part of the intended task workflow: **one task = one feature branch**. A user explicitly selects unpublished task commits to transfer; the adapter derives object changes and uses the connected base for platform-supported capture/commit. New storage revisions are verified and mapped to source Git SHA(s). Incoming storage changes are merged without overwriting active work. Git SHA and classical repository version remain distinct. See [Git workflow](docs/decisions/0004-local-git-task-branches.md).

[UI details](docs/STORAGE_UI.md) · [Architecture](docs/ARCHITECTURE.md) · [ADR 0003](docs/decisions/0003-optional-infobase.md) · [Roadmap](docs/ROADMAP.md) · [Rules](AGENTS.md)

**Builds only on explicit manual request**, not per commit. Do not use user VPS or real infobases/repositories without approval.

## Implemented receive prototype (unverified)

Open the project storage dialog, enter settings and click **Получить изменения**. The operation explicitly warns before modifying an already-connected infobase and mechanically checks that the selected EDT infobase matches the configured file/server address. EDT's native comparison dialog controls acceptance/conflicts. It never runs automatically. No stored repository revision is changed. [Full safety contract](docs/RECEIVE_CHANGES.md).
