# EDT Storage Bridge

> **ОБЯЗАТЕЛЬНО ПРОЧИТАТЬ ПЕРЕД РАБОТОЙ:** [Исходная задача и разбор ошибок разработки](docs/LESSONS_LEARNED.md). Требование закреплено также в [AGENTS.md](AGENTS.md). Главный механизм получения изменений из хранилища **ещё не реализован**; не выдавать за него настройки, XML-экспорт или прототипы.

Independently developed extension for **1C:EDT 2026.2** and classic 1C configuration repositories.

## Status

- Previous build #38938763356 passed against EDT 2026.2 API (Tycho/Java 25, 3 core tests).
- The project settings UI was compiled in the successful manual CI build #38944234655 but **has not been installed/smoke-tested in EDT**.
- New read-only history and **two-stage receive command** have been added **after that build**. These changes are UNCOMPILED and have not been tested in EDT.
- **Receive is disabled** until there is a proven selective merge path. The prior assumption that the repository-bound intermediary infobase was registered/linked to EDT was false; its EDT synchronization adapter has been removed.
- Working branch `feature/edt-2026.2-integration`; PR #6 remains draft until approved build and smoke test.

## Planned user flow

Right-click a project in EDT Navigator → **Работа с хранилищем**. The per-project dialog lets you save:

- repository address, user and securely encrypted password;
- one existing **classical-repository-connected 1C infobase** used as the storage transport (file directory or server/infobase name). It is **not** any arbitrary EDT development/test infobase;
- **optional** path to the installed platform executable (`1cv8.exe`);
- observed remote revision and verified locally applied revision (both unknown until real verification).

Saving settings never launches Designer. **Receive button is disabled**, so no mutating command is executable through the UI.

## Integration strategy

Prefer platform-supported operations through an existing connected infobase when needed. Direct supported read-only access remains a candidate for repository history and version queries. Before any receive or commit, inspect locks, identity and divergence and require explicit consent to modify the base. Local Git is part of the intended task workflow: **one task = one feature branch**. A user explicitly selects unpublished task commits to transfer; the adapter derives object changes and uses the connected base for platform-supported capture/commit. New storage revisions are verified and mapped to source Git SHA(s). Incoming storage changes are merged without overwriting active work. Git SHA and classical repository version remain distinct. See [Git workflow](docs/decisions/0004-local-git-task-branches.md).

[UI details](docs/STORAGE_UI.md) · [Architecture](docs/ARCHITECTURE.md) · [ADR 0003](docs/decisions/0003-optional-infobase.md) · [Roadmap](docs/ROADMAP.md) · [Rules](AGENTS.md)

**Builds only on explicit manual request**, not per commit. Do not use user VPS or real infobases/repositories without approval.

## Implemented receive prototype (unverified)

The new platform-side **selective object dump** is source-only and accepts an explicit list of changed metadata objects for /DumpConfigToFiles -listFile. It does not perform a full configuration XML export, EDT import, or modify the existing project. There is no completed end-to-end receive operation yet. [Status](docs/RECEIVE_CHANGES.md).
