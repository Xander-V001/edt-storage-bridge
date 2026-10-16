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
