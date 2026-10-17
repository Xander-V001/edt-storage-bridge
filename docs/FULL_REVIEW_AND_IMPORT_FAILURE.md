# Explicit complete review and native EDT import diagnostics

Status: source committed on `feature/edt-2026.2-integration`; no plugin build,
EDT 2026.2 integration test, or actual intermediary repository run yet.

## User command: «Полная сверка с хранилищем»

The settings dialog provides two separate actions:
- «Получить изменения» retains incremental native -listFile handling and
  optional pending-only retry.
- «Полная сверка с хранилищем» always attempts a fresh native
  `/ConfigurationRepositoryUpdateCfg -force` on the SAME existing
  intermediary, captures a fresh complete `ConfigDumpInfo.xml` inventory,
  saves all current metadata names as pending in the SAME per-branch pending
  journal, runs native `/DumpConfigToFiles` without `-listFile`, converts
  the WHOLE export via EDT's `IImportService.work()`, and opens the native
  compare editor with all materialized remote top-level owners.

No Git reset, repository rebind, detached project overwrite, SQL/DB changes in
the EDT-connected base, or automatic Git commit are part of this feature.
The existing comparison editor still prechecks some merge actions, so
developers must inspect choices before merging. A clean Git worktree is
required before starting the receive.

The marker `initial-full-review-prepared` is NOT reset on a forced full review:
ordinary future receives remain incremental. A full review is a fresh
operation, NOT a pending-only retry. Pending debt survives cancellation and
does not disappear merely because the full XML export completed.

A stale pending name absent from the **current** repository inventory
(e.g. remotely deleted metadata) is excluded from the full EDT import
scope but remains pending, explicitly announced as unresolved deletion/rename.
This avoids inventing a descriptor for a deleted object. Actual deletion
resolution and detecting local-only additions remain separate unfinished work.
"Full" denotes full SOURCE inventory comparison, not a guaranteed
byte-identical mirror or an automatic deleting reset of the user's EDT project.

## Failure at 2026-10-17 21:01:50

User-provided EDT log:
`EdtSelectiveReviewService.prepare:218` caused a generic `IOException`
at stage «Конвертация объектов и подготовка сравнения EDT».

In the previous implementation, this exact line represented:
`status == null || !status.isOK() || monitor.isCanceled()` after native
`IImportService.work()`. So the log demonstrates a failed/unsuccessful
native importer status **or cancellation**, not a missing owner after
successful conversion. There is no persisted `IStatus` tree in the old
log, therefore we cannot claim whether this was a harmless WARNING, an
XML root import failure, a missing referenced resource, or resource exhaustion.
Concurrent EDT jobs were also logged, including 6,350-object IB updates and
CPU/memory overload; they do not establish causality.

To avoid another useless generic IOException:
- Cancelled XML conversion is distinguished from a non-OK status.
- `NativeEdtImportFailure` records status severity/code and child count.
- It saves the full native `IStatus` tree, messages, exceptions and
  stack traces to a bounded local `%TEMP%/edt-storage-import-error-*.txt`
  on failure. The file is **potentially confidential** (object names, paths,
  1C internal messages): it is never silently posted, committed, or included
  verbatim in Eclipse Error Log. Review/redact before sharing.
- On a selective conversion failure the dialog directs the user to the
  separate explicit full-review action. There is **no hidden automatic
  fallback** which might consume multiple GBs without consent.
- Post-import owner checks still fail closed; diagnostic recording does
  NOT automatically treat ERROR/WARNING as successful.

## Proof / limits

Added regression tests in `RemoteReceiveScopePlannerTest` proving:
- Incremental selection includes only changed object identities;
- Forced full selects unchanged AND changed owners;
- A remote deletion remains pending instead of causing an invented
  missing `.mdo` owner or a mistaken "applied" status.

Added a synthetic Eclipse `MultiStatus` regression in
`EdtSelectiveReviewServiceTest` for local native status retention.

**The actual root cause of the 21:01 failure still requires a fresh
IStatus diagnostic (from the modified plugin), not a speculative guess.**
Do not claim the partial-import defect has been fixed in EDT.

Operational note: avoid running a simultaneous EDT upload/synchronization
of the project's *own* connected infobase during a 2+ GB native XML import.
The repository intermediary remains separate from the EDT-connected base.
