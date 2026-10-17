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

Metadata removed in the repository has no XML descriptor and must
NEVER be fabricated as a right-side object. It remains pending while the
full two-sided comparison exposes the original object on the EDT main side.
Only a verified deletion or a full match of a surviving parent can clear
the corresponding pending change. Local-only additions are also visible
in the editor, but are not deleted automatically.
"Full" means a complete source inventory and a complete main-side
inventory; it is not an automatic destructive mirror of the repository.

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

## 2026-10-17: Do not suppress native EDT merge deletions

The user demonstrated full comparison where local-only nested report forms appeared
orange ("В 'Текущий проект EDT'") but could not be removed by "Объединить".
The cause in the **active** `EdtSelectiveReviewService.prepare` was
`ComparisonProcessSettings.builder(...).mainSideObjectsDeletionAllowed(false)`.
It intentionally disabled the native editor's checkbox/delete behavior.

It is now `mainSideObjectsDeletionAllowed(true)` in BOTH normal selective
receives and forced full reviews. No extra application toggle is added.
According to the native EDT comparison rules, objects present ONLY on the
main (left) side are still unchecked by default: the user can explicitly
check the local-only rows in the standard EDT editor and merge to delete
them. Enabled deletion is NOT unconditional auto-deletion.

**Resolved in source**: The full comparison now scans the actual top-level
EDT `.mdo` descriptors from BOTH the project and imported staging. The
main scope is their union and the other scope contains the imported source.
This exposes main-only objects for the native editor's explicit deletion.
The verifier checks all imported primary owners, not just the subset that
has a `configVersion` token. Missing source objects still need an actual
verified removal before the incoming pending journal can clear.

Unlike full mode, selective mode retains a limited scope by design: it
now places separately versioned form, module, rights and template names
**together with their parent** in Designer `-listFile`, instead of dropping
the child identity. Configuration-root/Language special resources remain
deferred to the complete mode, with an explicit pending marker.

The newly enabled deletion setting remains **source-only until built and
smoke-tested** in installed EDT 2026.2. Previously opened comparison editor
instances must be closed and reopened with the updated plugin.

## October 17 completeness changes (source-only, build pending)

- `SelectiveDumpPlanner` preserves all separately versioned resources
  recognized by `ConfigurationChangesParser`: owner + exact child.
  Embedded attributes/commands still use their owner.
- The full native EDT comparison uses a UNION of local and remote primary
  metadata descriptors, not remote-only `ComparisonScope(scope, scope)`.
  This allows normal EDT deletion of local-only objects without an app toggle.
- Full import still calls `IImportService.work()` on the **whole** XML folder
  without hiding root `Ext` resources. Only selective import has the scoped
  root-`Ext` workaround.
- Deleted metadata from a previous `ConfigDumpInfo.xml` remains in the
  incoming journal. Once EDT removes it, the verifier can confirm absence of
  both directory and flat `.mdo`. Nested deleted objects are checked through
  their surviving parent owner.
- A full receive verifies every primary descriptor materialized in the
  temporary imported EDT tree before raising the applied-revision checkpoint.
  A checked merge option or closing the editor is not a substitute for
  content verification.
- Correctness has **not** been validated by installed EDT 2026.2, and CI
  remains manual by project policy. Tests were added but not yet executed.
  The two-sided full-scope semantics and real 1C selective -listFile
  behavior must be exercised with actual new/deleted forms before release.

Safety restrictions still intentionally in place: verified connection,
Git branch/HEAD stability, failing closed on invalid 1C metadata,
unknown converted owners and incomplete import, preserving pending entries
until independently verified, and requiring explicit merge selections.
These are not restrictions on which valid configuration objects a user may
choose to transfer.

## 2026-10-17: No application-defined restrictions on FULL native comparison

**Critical correction to the prior design**: manual `ComparisonScope(main,other)`
selection based on `ConfigDumpInfo.xml` or scans of top-level `.mdo`
owners is **not** a full native comparison. It can omit native model
differences in nested objects, form properties, pictures, support files or
metadata that have no separately versioned owner token.

The full receive pipeline now explicitly uses:

```text
1C intermediary: /DumpConfigToFiles <folder> -Format Hierarchical
    NO -listFile
  -> EDT IImportService.work(project, wholeXmlFolder, cleanStage, monitor)
    NO EdtRootXmlGuard / NO application-defined type allowlist
  -> ComparisonProcessHandle(
       V8ProjectComparisonDataSourceDescriptor(activeProject),
       FileSystemComparisonDataSourceDescriptor(cleanStage, projectName),
       ComparisonScope.EMPTY_SCOPE)
  -> ComparisonProcessSettings:
       parseBslModuleStructure(true),
       mainSideObjectsDeletionAllowed(true)
  -> unmodified native EDT compare/merge editor
  -> read-only independent resource verification and pending journal
```

`ComparisonScope.EMPTY_SCOPE` is the **official native EDT scope** with
no objects on either side, zero exclusions, and no locked removable objects.
`IComparisonSession.isGlobalScope()` defines a GLOBAL comparison session as
one without any scoped object on either side. This allows EDT itself to
enumerate all objects, including remote-only and local-only metadata and
their complete nested resources. Do not reintroduce a manually generated
owner/metadata scope in the full path.

Authoritative EDT API documentation:
- https://edt.1c.ru/dev/edt/2025.2/apidocs/com/_1c/g5/v8/dt/compare/core/ComparisonScope.html
- https://edt.1c.ru/dev/edt/2025.2/apidocs/com/_1c/g5/v8/dt/compare/core/IComparisonSession.html

The full-mode incoming journal still reads native `ConfigDumpInfo.xml` to
track version deltas, but it MUST NOT determine what native EDT is allowed
to import or compare. Unknown names in that inventory must not be excluded
from a full comparison. EDT importer failure, cancelled operations and unsafe
temporary directories still fail closed to avoid corrupting the project.

**Selective receive** keeps a narrow native comparison scope, but now builds
its listFile from all current independently versioned descendants under
each changed top-level owner: this includes additional unchanged forms,
modules and templates needed to represent the whole changed object. Nested
Subsystem paths retain their actual full hierarchical names. The separate
Configuration-root/Language partial-import limitation is unchanged; those
resources are handled by the unrestricted full-review path.

A full result may still DIFFER from a repository CF even after an
unrestricted comparison: the user must explicitly choose what to merge, and
the native importer/EDT-to-CF conversion may have its own limitations.
Five historical source-vs-live differences, including `Picture.png`,
remain under investigation. Never falsely mark a revision as fully received
until results are independently verified.

**Validation**: source edits and offline regression tests added, but no
Tycho/OSGi CI build or installed EDT smoke test has yet been run for this
commit. Manual-only workflow, no VPS usage.
