# План доведения получения из классического хранилища до рабочего механизма

**Цель:** в уже существующем проекте 1С:EDT 2026.2 выбрать и принять изменения только нужных объектов из классического хранилища 1С, не потеряв локальные закоммиченные изменения Git. Операция должна быть инкрементальной, проверяемой и восстанавливаемой после сбоя.

**Статус:** сборка #24 прошла Tycho и 25 тестов; при первом запуске в EDT 2026.2.1.3 получение завершилось `IOException` без этапа/stack trace. В текущей ветке после #24 есть исправления, **ещё не собранные и не проверенные в установленной EDT**. Полный сценарий получения не подтверждён.

## Неизменяемые ограничения

- Существующее классическое хранилище 1С; одна уже существующая ИБ, подключённая к хранилищу, **не связанная с EDT**; существующий EDT-проект и его Git.
- Никаких дополнительных пользовательских баз, проектов EDT, служебных Git-веток/worktree; никакой полной выгрузки большой конфигурации для каждого обновления.
- Другие ИБ, связанные с EDT-проектом, не участвуют в обмене.
- Никаких операций на рабочей ИБ/VPS и никаких GitHub Actions без отдельного явного разрешения.
- До подтверждённого runtime smoke-test рабочую кнопку получения не считать безопасной.

## Принятая архитектура

```text
Classic repository (revision N -> N+1)
   -> existing connected 1C intermediary (Designer commands)
   -> previous ConfigDumpInfo.xml + incremental change list
   -> explicitly selected changed metadata objects only
   -> XML export only selected objects, temporary directory OUTSIDE Git/EDT
   -> official IImportService (XML -> external EDT source representation)
   -> native EDT Compare/Merge: active project versus external EDT source
   -> user reviews and accepts/rejects each changed object
   -> verify resulting source and Git identity
   -> record accepted objects; keep rejected/conflicted objects pending
```

*Read-only operations precede any writes; the sole initial write is an **explicitly confirmed** update of the existing connected intermediary. The EDT project changes only through user-confirmed native comparison.* A two-way comparison can support explicit selective acceptance, but does not automatically identify divergent *committed* changes. A true three-way comparison requires a separately verified storage-revision ↔ Git ancestor mapping. Never infer this from a clean working tree.

## Ordered work packages: one vertical slice, no parallel features

### P0 — Contain first runtime failure

**Change:** retain original failure stage and sanitized diagnostic details; verify preconditions before modifying intermediary. Investigate `IOException` using the new stage log; old log cannot establish the original cause. Verify whether a durable snapshot of the failed receive survives, and NEVER discard it or retry the intermediary update automatically.

**Stop/go:** repeated click or cancellation cannot silently run a second update when the previous outcome is unknown. A failure message must identify the failing stage and what state may have been touched.

### P1 — Prove 1C Designer transport against disposable fixtures

**Change:** isolate and test the actual CLI commands and reports, not Java syntax alone:
1. Read pre-update configuration `ConfigDumpInfo.xml` without exporting the entire configuration.
2. Verify connected intermediary identity, storage identity, authentication mode and observed revision; fail closed if identity or permissions cannot be established.
3. Confirm `/ConfigurationRepositoryUpdateCfg` on a disposable connected IB only, check actual return code, 1C log and post-update version.
4. Run documented `/DumpConfigToFiles ... -update -configDumpInfoForChanges <old info> -getChanges <list>`; strictly parse only the changed set, including deletion/renaming signals (stop if unsupported).
5. Verify `-listFile` exports only explicitly selected metadata and required children, with no full-export fallback.

**Stop/go:** for repository N -> N+1, a modified Document2 produces an explicit report/export of Document2 and not unrelated objects. Unsupported changes produce a clear refusal, never a fake "no changes".

**Evidence:** real command argv, exit statuses, carefully redacted Designer logs, exact set of exported metadata in a disposable 1C fixture. Official 1C reference: https://kb.1ci.com/1C_Enterprise_Platform/What___s_New/Functional_Highlights/Incremental_export_of_configurations_to_XML_files/

### P2 — Prove native EDT conversion and comparison independently

**Change:** test `EdtSelectiveReviewService` / `EdtDeltaComparisonService` with a tiny, already prepared partial XML fixture. Use external destination via four-argument `IImportService.work(project, source, destination, monitor)`, not the overload that writes into the project. Derive valid metadata FQNs and comparison scope using EDT API; test whether the native compare editor actually opens and can accept/reject an individual object in 2026.2.1.3.

**Stop/go:** no selected content changes in active EDT until explicit review; accepting Document2 modifies only Document2; refusing Document1 leaves Document1 byte-for-byte intact. Include child Forms/Templates and root Configuration differences in explicit handling; unsupported cases fail closed.

### P3 — Preserve local Git commits and remote pending changes

**Change:** enforce branch + HEAD identity from before receive through acceptance; preserve Git worktree changes created by explicit merge while never checking out/resetting/rebasing. For an initial two-way receive require explicit review of each selected object; **no automatic conclusion that committed objects are unchanged**. If verified common ancestor is available, activate 3-way comparison and classify local committed differences via Git diff from that ancestor. Otherwise preserve/skips conservatively and label comparison as two-way.

**Persistence:** durable journal keyed by project, repository, branch and validated source revision/snapshot. Atomic state transitions `SNAPSHOT -> UPDATE_ATTEMPTED -> UPDATED_VERIFIED -> CHANGES_JOURNALED -> REVIEW -> ACCEPTED/PENDING`. Failed/crashed/cancelled operations preserve stage and pending, never invent a globally applied revision.

**Stop/go:** with locally committed Document1 and remotely modified Document1+Document2, accepting only Document2 preserves Document1 and its commit; Document1 remains visibly pending on restart; verified global applied version is not advanced.

### P4 — Automated regression suite BEFORE installation

**Change:** test command-line arguments, XML change parsing (add/edit/delete/rename), selective names and children, invalid output/exit codes/timeouts/cancellation, recovery after interruption at each state transition, Git branch/HEAD change, pending persistence and file-scope checks. Cover no-op N -> N and repeated N -> N+1. For EDT-specific services add an integration exercise, not mocks alone.

**Stop/go:** all tests green AND integration fixture demonstrates the above invariant. Tycho `BUILD SUCCESS` alone is not accepted as proof of working receive.

### P5 — Windows EDT 2026.2.1.3 acceptance

**Environment:** disposable local configuration repository and existing connected test intermediary plus expendable EDT project/Git; NO production IB. Fixtures may be prepared only with separate authorization.

**Mandatory scenarios:**
1. N -> N+1, only Document2 changes: list/export/import and merge ONLY Document2.
2. N -> N+1, committed local Document1 and both remote Document1+Document2 change: leave Document1 unchanged, accept Document2; restart EDT; Document1 stays pending.
3. Cancel before write, cancel after update, Designer failure and EDT crash while review is open: no untracked silent retry, no vanished pending objects, no unexpected project modification.
4. Compare edited children (form/template), new object and deletion/rename: demonstrate support or explicit safe refusal.

**Evidence:** exact files changed, Git HEAD/worktree diff, local SHA/content of Document1 before/after, pending journal state, pre/post storage versions, redacted logs, native editor screenshot/smoke results. Only after these pass may receive be called functional.

## Shipping gate

Only user-approved manual GitHub Actions build, then verify artifact installs in Windows EDT 2026.2.1.3 and that all mandatory P5 tests pass. No claim that CI compilation validates the runtime. No operations on live users' bases until the isolated acceptance passes and user authorizes real use.

## Immediate priority

**Not another UI feature, CI run or general rewrite.** Start with P0/P1: verify the actual 1C command contract, authentication, version identity and recoverability; then P2 native EDT compare proof; then join those two already-proven components in P3. If P1/P2 cannot be demonstrated independently, stop and report the precise missing API/constraint instead of adding more scaffolding.
