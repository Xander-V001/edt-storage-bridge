# EDT Storage Bridge

> **ПЕРЕД РАБОТОЙ:** [Исходная задача и разбор ошибок разработки](docs/LESSONS_LEARNED.md) · [План приёмки](docs/RECEIVE_ACCEPTANCE_PLAN.md) · [AGENTS.md](AGENTS.md). **Сборка #24 прошла, но первое получение в EDT завершилось ошибкой. Изменения после #24 пока не собраны и не проверены в EDT. Плагин нельзя считать рабочим.**

Independently developed extension for **1C:EDT 2026.2** and classic 1C configuration repositories.

## Status (2026-10-17)

- Manual CI [#24](https://github.com/Xander-V001/edt-storage-bridge/actions/runs/38975593867) successfully compiled the EDT 2026.2 bundles, passed 25 OSGi tests, and produced the p2 installation archive.
- First real receive in the installed EDT 2026.2.1.3 failed with `IOException`; the old handler dropped the failing stage and diagnostic details. End-to-end receive is **not functional/verified**.
- Since build #24, the code includes stage diagnostics, corrected incremental dump flags, durable retry guards, intermediary identity binding, and optional repository authentication. These edits **have not been compiled or smoke-tested**.
- Existing intermediary infobase bound to classical repository is separate from all EDT-connected infobases. Production testing is prohibited. Working branch `feature/edt-2026.2-integration`; PR #6 remains unmerged.

## Planned user flow

Right-click a project in EDT Navigator → **Работа с хранилищем**. The per-project dialog lets you save:

- repository address, user and securely encrypted password;
- one existing **classical-repository-connected 1C infobase** used as the storage transport (file directory or server/infobase name). It is **not** any arbitrary EDT development/test infobase;
- **optional** path to the installed platform executable (`1cv8.exe`);
- observed remote revision and verified locally applied revision (both unknown until real verification).

Saving settings never launches Designer. A separate **Получить изменения** button now has a confirmed, explicitly approved read/update/stage/compare flow in source. Its latest corrections are **UNBUILT and must not be used with live data**.

## Integration strategy

Prefer platform-supported operations through an existing connected infobase when needed. Direct supported read-only access remains a candidate for repository history and version queries. Before any receive or commit, inspect locks, identity and divergence and require explicit consent to modify the base. Local Git is part of the intended task workflow: **one task = one feature branch**. A user explicitly selects unpublished task commits to transfer; the adapter derives object changes and uses the connected base for platform-supported capture/commit. New storage revisions are verified and mapped to source Git SHA(s). Incoming storage changes are merged without overwriting active work. Git SHA and classical repository version remain distinct. See [Git workflow](docs/decisions/0004-local-git-task-branches.md).

[UI details](docs/STORAGE_UI.md) · [Architecture](docs/ARCHITECTURE.md) · [ADR 0003](docs/decisions/0003-optional-infobase.md) · [Roadmap](docs/ROADMAP.md) · [Rules](AGENTS.md)

**Builds only on explicit manual request**, not per commit. Do not use user VPS or real infobases/repositories without approval.

## Receive flow wired in sources (NOT VERIFIED)

1. User explicitly confirms updating **only the existing classical-repository-bound intermediary**. The EDT project's other attached bases are never accessed.
2. Capture a **ConfigDumpInfo.xml-only** pre-update baseline; persist it outside the EDT project so a crash cannot lose newly changed objects.
3. Update the existing intermediary with the platform's native repository command, detect changed MD objects using `-getChanges` and refuse `FullDump` fallback.
4. Persist remote-object debt **per project/repository/user Git branch**, including previously skipped objects. Show a list with **nothing preselected**; the user chooses precisely which objects to review.
5. Export only the selected objects with `-listFile`, convert to an isolated EDT source folder with the official `IImportService`, derive native top-level object FQNs and open the **existing EDT compare/merge editor**.
6. On editor close, clear a pending object only if all its staged EDT source files exactly match the resulting project. Otherwise leave it pending. **Never advance an invented repository-wide applied revision**.

**Open technical and verification limits:** This first interactive mode is TWO-WAY: it protects unselected locally committed objects by leaving them entirely outside the comparison scope, but does not claim a repository/Git three-way common ancestor. The separate three-way adapter requires a verified Git/storage baseline not yet provisioned. Repository version history parsing, objects deleted from storage, pre-existing storage/EDT divergence (if the connected IB was already updated independently), and unsupported object-type mappings require additional proof. The code compiled successfully in CI #24, but the runtime receive failed in EDT 2026.2.1.3. Edits after #24 have not yet been built; there is no verified working p2 release.

See [Detailed implementation](docs/RECEIVE_CHANGES.md).
