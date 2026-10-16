# Storage receive — actual actors, current implementation

Status: **NOT FUNCTIONAL in EDT; source-only; build and runtime unverified.**

There are exactly three actors:
1. Classical 1C configuration storage (shared by developers).
2. One pre-existing 1C infobase **bound to that classical storage**. This intermediary is **NOT related to EDT**.
3. One existing EDT project with local Git main/task branches. It can have 0–100+ separately connected bases; they are irrelevant to storage receiving.

## Verified platform primitives

- `/ConfigurationRepositoryUpdateCfg` updates the *repository-bound intermediary* from classical storage. The platform supports `-Objects` for selective receive; it supports `-force` to confirm additions/deletions. No `-revised` by default.
- `/DumpConfigToFiles <directory> -listFile <objects.txt> -Format Hierarchical` exports **only** the explicit metadata objects from that intermediary. Object list format is 1C full metadata names, one name per line. This is not full XML export.
- The platform additionally supports `-update` using `ConfigDumpInfo.xml` for incremental updates of an existing export, but **this mode is not yet implemented in our bridge**.

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
