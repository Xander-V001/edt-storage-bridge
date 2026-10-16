# Critical path first: storage → existing EDT project (EDT 2026.2)

**Decision:** This is a BLOCKING proof-of-concept gate, before extending history, parsers, UI or optimizing incremental retrieval. We previously implemented the read-only report foundation without first demonstrating how to apply changes into an existing EDT project. Correct that development order.

## Supported facts and candidate APIs (do not conflate)

1. Official EDT developer manual states that **an existing EDT project can be updated from an infobase**; EDT compares/merges and asks the user to resolve conflicts:
   https://its.1c.ru/db/content/edtdoc/src/topics/i032.html
2. 1C:EDT **2025.2** published API lists `com._1c.g5.v8.dt.internal.platform.services.ui.infobases._import.ImportConfigurationChangesJob`, whose documented purpose is import of configuration changes from an infobase to existing EDT projects. However **this class is INTERNAL, must not be a permanent integration dependency without an explicit compatibility decision and isolation**:
   https://edt.1c.ru/dev/edt/2025.2/apidocs/com/_1c/g5/v8/dt/internal/platform/services/ui/infobases/_import/ImportConfigurationChangesJob.html
3. Public candidate `com._1c.g5.v8.dt.import_.IImportService` has `work(IProject existingProject, Path sourceDirectory, IProgressMonitor)`. It imports platform XML into **an existing** EDT project. This is a lower-level import mechanism, NOT independently proven to preserve 3-way merge/conflict workflows or deletions:
   https://edt.1c.ru/dev/edt/2024.2/apidocs/com/_1c/g5/v8/dt/import_/IImportService.html
4. Official EDT CLI offers `import --configuration-files ... --project ...` and `infobase-import` modes; investigate in the **2026.2** installed CLI, because the commands have different semantics and generic CLI import is not automatically an equivalent to merge:
   https://its.1c.ru/db/content/edtdoc/src/topics/t000608.html

The above APIs are evidence from 2025.2 / earlier documentation. **None has been checked against the installed EDT 2026.2 platform**. Do not state otherwise.

## Minimum vertical-slice proof

1. Create a disposable connected classic 1C repository and two independent test infobases/projects (user permission before any real command). Record baseline objects and version.
2. Make one controlled server-side object change (e.g. a test common-module comment), receive into the connected test infobase using the Designer's *supported* repository update.
3. On EDT 2026.2, verify a comparison preview **before** applying changes to the active project, with selectable metadata objects; imported state must initially remain isolated.
4. Find a stable public API or EDT CLI that reproduces the same operation programmatically. Verify supported exported packages and actual available OSGi services in 2026.2.
5. If no supported programmatic merge API exists, present a clear trade-off: supported UI wizard delegation, or staged XML import with explicit compare/conflict detection. Do NOT silently invoke internal `ImportConfigurationChangesJob`.
6. Repeat with a divergent local edit to the same object, a new object and a deleted object. Confirm the merge is safe and no uncommitted data is lost.
7. Add an explicit **Get changes → compare → select objects → apply** flow restricted to a disposable fixture. Do not create technical Git branches.
8. Only after verified success of **all** changes may the complete applied repository version advance; skipped objects require separate pending-state tracking. Abort/cancel/uncertain result leaves checkpoint unchanged.

## Architecture after proof

- Report/history command: discovery only; not a substitute for receiving to EDT.
- Existing connected base: platform-backed repository operations, but it must not be mutated without authorization.
- EDT import: selected, tested integration of **existing project** rather than new project. May use exact EDT 2026.2 public API or supported CLI, with full comparison/merge semantics verified.
- Local Git is a required user-task feature: only developer branches **main + one feature branch per task**. No technical Git branches/worktrees. The existing project's working tree is never reset or silently checked out.
- Proof must track the last applied storage revision **per branch** with a corresponding Git baseline SHA, preserve local commits during receive and verify merging of a storage change on a branch with unrelated local task commits.
- For reverse flow, prove conversion of a selected, contiguous range of *unpublished* Git commits into concrete metadata-object changes, then a verified repository write through the connected base. Git commit hash and 1C revision number are not interchangeable.

## Implementation update (source only)

The first guarded receiving *vertical slice* now exists in source. It calls the public `IInfobaseSynchronizationManager.retrieveInfobaseChanges` API after a user-confirmed Designer `/ConfigurationRepositoryUpdateCfg -force` into an existing connected infobase and uses an EDT-provided conflict resolver without any "override project" operation. It is NOT YET COMPILED OR TESTED ON EDT 2026.2, and it **does not yet present obligatory selective compare before apply**, so it must not be used on a working base. No update of last applied repository version is performed. Full documented contract: [RECEIVE_CHANGES.md](RECEIVE_CHANGES.md).

## Current status

[ ] Public documented implementation path confirmed at runtime in EDT 2026.2
[ ] Minimal end-to-end receive completed on disposable fixture
[ ] Conflict/new/delete semantics verified
[x] Command integrated with form in source; runtime unverified
[ ] Repository applied-version checkpoint verification implemented

Until these checkboxes are proven, claiming a working synchronization engine is prohibited. **No new CI build, live base access or test command has been run for this document.**
