# Storage receive — actual actors, current implementation

Status: **NOT FUNCTIONAL/ACCEPTED in EDT**. CI #24 compiled an older revision, but the first installed receive failed; fixes after #24 are source-only and NOT compiled/runtime-verified.

There are exactly three actors:
1. Classical 1C configuration storage (shared by developers).
2. One pre-existing 1C infobase **bound to that classical storage**. This intermediary is **NOT related to EDT**.
3. One existing EDT project with local Git main/task branches. It can have 0–100+ separately connected bases; they are irrelevant to storage receiving.

## Verified platform primitives

- `/ConfigurationRepositoryUpdateCfg` updates the *repository-bound intermediary* from classical storage. The platform supports `-Objects` for selective receive; it supports `-force` to confirm additions/deletions. No `-revised` by default.
- `/DumpConfigToFiles <directory> -listFile <objects.txt>` exports **only** the explicit metadata objects from that intermediary. Object list format is 1C full metadata names, one name per line. This is not full XML export.
- `ChangedObjectsProbe` now uses `-update -getChanges -configDumpInfoForChanges` to determine only changed objects relative to a stored version file; this command has not yet been proven on a disposable 1C installation.

Official documentation:
- https://kb.1ci.com/1C_Enterprise_Platform/Guides/Administrator_Guides/1C_Enterprise_8.3.27_Administrator_Guide/Appendix_7._Startup_command-line_options_of_1C_Enterprise/7.4._Running_Designer_in_batch_mode/7.4.15._Configuration_repository_operations/
- https://kb.1ci.com/1C_Enterprise_Platform/What___s_New/Functional_Highlights/Incremental_export_of_configurations_to_XML_files/

## Source added now

- `SelectiveDumpRequest` accepts **non-empty, validated** metadata names and one existing /F or /S intermediary address.
- `SelectiveDumpCommand` produces the read-only and strictly selective Designer command; no full-configuration fallback, no `-force`, no EDT base selection.
- `SelectiveDumpRunner` writes the explicit UTF-8 object list and exports the selected objects into a private temporary directory. It bounds the process lifetime, redirects stdout, handles cancellation, validates nonempty result and owns temp cleanup via AutoCloseable.
- Pure JUnit command tests were added but have **NOT** been executed.
- Removed incorrect `EdtReceiveService`, EDT base-selection controller and matching class; UI command is disabled until a safe merge can be verified.

## What is still missing

- Read changed object names/revisions correctly from classical storage history, including additions, deletions and dependencies. **No parser-to-export wiring yet.**
- Convert selected 1C XML files into equivalent EDT-format source using verified 2026.2 import API or equivalent, **without** reimporting the entire configuration.
- Show native compare/merge between selected incoming objects and current project. Respect explicit "keep my Document1" while applying other objects; no silent overwrite.
- Track skipped/pending objects independently of the latest repository version; don't claim full sync after partial acceptance.
- Prove the full chain in a disposable fixture and build only after explicit authorization.

**No attached EDT bases are used for storage operations. No production infobase has been touched, and no CI build was launched.**

## Source added after initial selective-export prototype — NOT YET WIRED

- `ChangedObjectsProbe` invokes Designer `/DumpConfigToFiles -getChanges -configDumpInfoForChanges` against an **existing verified** `ConfigDumpInfo.xml`, using the already configured repository-bound base; no EDT base lookup. The separate `ChangeDetectionRequest` does not require object names.
- `ConfigurationChangesParser` strictly parses recognized New:/Modified: entries into top-level metadata names. **FullDump and unknown/removal/rename records STOP with an error.** No full export fallback. It does not silently declare deletions absent.
- `EdtDeltaComparisonService` passes partial XML through the **four-parameter** public `IImportService.work(project, xml, externalDestination, monitor)`, prepares `V8ProjectComparisonDataSourceDescriptor`, `FileSystemComparisonDataSourceDescriptor(output, projectName)` and `GitComparisonDataSourceDescriptor` for three-way comparison, and constructs native comparison editor input. It never calls the mutating three-argument import overload. EDT compare editor is **not opened by UI yet**.
- `GitObjectDivergenceGuard` inspects committed Git diff since an explicitly verified baseline SHA, checks that it is an ancestor of HEAD, and classifies changed EDT files by top-level object. An unknown file blocks unattended safe classification. A clean working tree by itself is insufficient.
- `EdtPathObjectMapperTest`, `ConfigurationChangesParserTest` were added as source; **neither compiled nor executed**.

**Still missing:** provisioning/validating the initial ConfigDumpInfo baseline and its relation to a classic storage version, invoking current Designer update after permitted preflight, mapping classic 1C MD names to EDT symlinks, selecting the actual EDT importer implementation, opening the EDT editor through compatible runtime API, preserving pending skipped objects and end-to-end conflict test. These components are source-level integration seams, **not a working receive button**. Build and test remain manual only after explicit user request.

## Version-only baseline capture and selective staging (2026-10-17, source only)

- `ConfigVersionSnapshotRunner` uses **only** `/DumpConfigToFiles -configDumpInfoOnly -Format Hierarchical` against the existing repository-bound base, producing a temporary `ConfigDumpInfo.xml` without all metadata XML. This baseline is valid ONLY for the base's state at that moment; it **must not** be labeled an earlier repository revision without verification. No production operations were run.
- `SelectiveReceiveStagingService` now joins `ChangedObjectsProbe.detect` to `SelectiveDumpRunner.export` for the SAME existing base and carries the staged XML as a Closeable result. Empty changes skip export; `FullDump` and unknown change types fail closed.
- `ConfigurationChangesParser.objectsToExport` now includes independently developed `Form`, `Template`, `Recalculation` and `Subsystem` children explicitly, instead of losing them by exporting only the parent. Includes a regression source test.
- Still NOT built/tested under EDT 2026.2 and NOT connected to receive UI. Missing verified repository-revision-to-baseline relationship, EDT scope mapping, compare-editor opening and merge outcomes/checkpoints. No user baselines or Git refs were touched.

## 2026-10-17: interactive receive coordinator (source wired, build unverified)

- `ReceiveChangesCoordinator` connects snapshot → native repository update (separately confirmed by the user) → incremental `-getChanges` → branch-scoped pending journal → **explicit, initially empty selection** → selected-object dump → EDT external conversion → native review editor.
- `ReceiveBaselineJournal` retains the old `ConfigDumpInfo.xml` outside user Git, even if 1C Designer updates its intermediary but EDT crashes before processing changes. Recovery never overwrites the earlier snapshot.
- `ReceivePendingStore` tracks remote object debt by (EDT project directory, classical repository address, actual Git branch). Skipped objects remain eligible on later receives.
- `EdtSelectiveReviewService` derives compare scope using EDT's official `IQualifiedNameFilePathConverter` rather than pretending Russian object type names are EDT symlinks. Only recognized metadata kinds are accepted; other cases fail closed.
- `StorageSettingsDialog` now wires `Получить изменения` to coordinator, and `VerifiedReceiveContent` checks staged files against EDT project after native editor closure. Matching objects can be cleared, other objects remain pending.
- This source flow does not automatically determine a true storage/Git common ancestor, and therefore does **not** pretend Git HEAD is the repository source. Objects are *not selected* by default. Not supported yet: confident detection of already-updated intermediary vs unrefreshed EDT branch; revision-history checkpoints verified against classical storage; safe deletion handling; arbitrary unknown object types; roundtrip smoke test and manual build.
- **Do not call this a functioning installer** until EDT 2026.2 target compile and a disposable Document1/Document2 test both succeed. No CI/build or real IB access was used while editing these sources.


## Runtime failure observed in installed 1C:EDT 2026.2.1.3 (2026-10-17)

Build #24 successfully compiled and packaged the plugin (25/25 OSGi unit tests),
but installation/runtime receiving was **not validated** by that CI run.
The user's EDT log for 2026-10-17 01:38:19 contains only
`Storage receive failed: IOException`; the previous handler discarded the
throwing stage, stack frames, and the actual exception message. The root
cause of that individual runtime failure therefore cannot be established from
that log alone. Older, unrelated EDT metadata-validation entries must not be
attributed to the bridge.

Corrections after build #24 (NOT YET BUILT OR RUNTIME-VERIFIED):

- `ChangedObjectsProbe` now passes `-update` together with
  `-getChanges` and `-configDumpInfoForChanges`, as required by the 1C
  command-line contract. It never passes `-force` for detecting changes.
- Added a headless JUnit regression test for incremental Designer argv and
  version-only baseline capture; the test has not yet been executed by CI.
- `ReceiveChangesCoordinator` now reports which stage failed and writes
  exception **types** and own-code stack locations to the EDT error log.
  It does not write arbitrary exception messages, shell arguments,
  connection strings or passwords.
- It explicitly records whether updating the classical-repository-connected
  infobase was attempted, and warns that its state may be uncertain after a
  failure. The configured 1C executable and file-infobase directory are checked
  before confirmation or any mutating operation.

**Safety gate:** Until the actual infobase's post-failure state and stored
baseline have been verified, do not retry receiving on a working infobase.
No new GitHub Actions build was dispatched by these source-only changes.


## 2026-10-17: safe-retry and authentication corrections (SOURCE ONLY)

- **Crash/retry guard:** `ReceiveAttemptLedger` persists `SNAPSHOT_READY -> UPDATE_ATTEMPTED -> UPDATE_COMPLETED -> CHANGES_JOURNALED` outside Git. The `UPDATE_ATTEMPTED` marker is flushed **before** invoking native Designer; a failed/uncertain update is blocked on the next invocation, instead of being silently re-run. Only `UPDATE_COMPLETED` may resume read-only changes detection; fully journaled changes may finish cleanup. An unmarked snapshot from the installed #24 cannot be assumed safe and requires manual assessment.
- **Source identity:** `ReceiveBaselineJournal` now binds its snapshot to a fingerprint of the specified connected intermediary. `ReceivePendingStore` similarly binds pending object names and refuses a changed intermediary or ambiguous legacy pending list. These are guards against interpreting old changes as coming from a different database.
- **Repository authentication:** previously saved repository address/name/password were ignored by the update command. `RepositoryUpdateRequest` and `RepositoryUpdateCommand` now pass `/ConfigurationRepositoryF`, `/ConfigurationRepositoryN`, and optional `/ConfigurationRepositoryP`. The command is never printed in our diagnostic log. **Security:** native Designer credentials passed via command-line arguments may be visible to privileged local OS users. The confirmation dialog explicitly warns about this. IB authentication (`/N`, `/P`) is not yet implemented; separate testing/decision needed.
- **Scope:** no automatic EDT-connected infobase selection, no automatic Git reset/checkout, no automatic clearing of object-level pending status; the native two-way compare remains unverified.
- **Tests:** added restart/uncertain-state tests and repository-command authentication tests. No user-unapproved GitHub Actions build was dispatched.

### Work still required before real use

1. Prove an existing repository-bound intermediary matches the configured repository (read-only preflight). The current command-line authentication fields do not themselves demonstrate this relationship.
2. Verify the new CLI combinations and actual 1C returns on disposable data; include errors, cancellation and missing IB login credentials.
3. Validate partial XML-to-EDT conversion and native EDT compare/merge in EDT 2026.2.1.3 with Document1/Document2, child forms/templates and committed Git divergence.
4. Do not retry using the installed #24 on working data. The new source fixes are **uncompiled**, do not constitute a usable release or prove safe runtime behavior.


## 2026-10-17: background progress in Eclipse / EDT (source only)

- `ReceiveChangesCoordinator.receive` now schedules a user-visible `org.eclipse.core.runtime.jobs.Job` (`setUser(true)`), with `SubMonitor` allocations across Git preflight, existing intermediary update/change detection, selective export, and external EDT conversion. Standard Eclipse **Progress** view/status area displays the job and subtask information. EDT code editors are no longer held behind a long-running modal `ProgressMonitorDialog`.
- Designer operations, Git checks and external-format conversion run in the Job worker. User confirmations, the explicit object-selection dialog, and native EDT compare editor opening/listener setup are marshalled to SWT via `Display.syncExec`, while completion/error notices are posted asynchronously.
- Cancellation is cooperative via `IProgressMonitor.isCanceled()`; an already started native Designer update has uncertain outcome if interrupted and remains protected by the existing durable retry guard. Neither cancellation nor a Job status grants authorization to use a production intermediary.
- Source change has not been compiled, installed, or accepted in EDT 2026.2. Do **not** mistake visible Job progress for a verified correct receive/merge algorithm. The Document1/Document2 disposable acceptance test remains mandatory.


## 2026-10-17: reuse of proven 1C-Updater transport

Reviewed `Xander-V001/1C-Updater/src/OneCUpdater/SharedRepositorySource.cs` and `UpdateEngine.cs`. The previous Windows app provides distinct credentials for (1) the existing repository-bound **infobase** (`/N`, `/P`) and (2) the **classical repository** (`/ConfigurationRepositoryN`, `/ConfigurationRepositoryP`). It updates the existing connected intermediary via `/ConfigurationRepositoryUpdateCfg -force`, sets all three native startup suppression options, and retains its `/Out` diagnostic reports. Its subsequent `/DumpCfg` produces a complete CF and cannot replace EDT's incremental `/DumpConfigToFiles` + selective merge.

The current bridge had been missing the *infobase* user/password entirely. They are now separate fields in the EDT settings dialog, securely persisted via Eclipse Secure Storage and passed through initial ConfigDumpInfo snapshot, classical repository update, incremental change detection, and selected-object XML export. The commands use the same `/DisableStartupDialogs /DisableStartupMessages /DisableUnrecoverableErrorMessage` switches.

Build #26 previously failed at `ConfigVersionSnapshotRunner.capture` (native Designer snapshot command) with no useful reason because exit status was discarded and raw native output deleted. This revision retains native `/Out` and stdout files on a snapshot failure in a local temporary folder; it shows the exact process exit code and folder to the user without writing potentially sensitive native log contents to the EDT error log. Treat the retained native files as sensitive. Successful snapshot temporary files are still deleted.

**Unverified:** actual cause of the native failure; native incremental XML command compatibility; EDT comparison/merge and committed local changes. These source fixes do not constitute a working release. No CI build or real infobase test has been run without user authorization.
