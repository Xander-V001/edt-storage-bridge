# Real classic repository revision numbers in EDT Storage Bridge

Status: **source code added, NOT built or validated in installed EDT 2026.2**.

## Why there are two independent revision fields

1. "Последняя обнаруженная версия хранилища" is the native **latest
   repository revision** observed by an explicit read-only Designer report.
   It is not `ConfigDumpInfo.xml`'s per-object `configVersion` token.
2. "Версия, полностью применённая к EDT" is the last revision for which
   this branch's outstanding incoming object queue was emptied by the
   post-merge byte-content verification. A merely opened comparison editor
   or a checked box NEVER advances the applied checkpoint.

## Read-only version query

The documented command is
`/ConfigurationRepositoryReport <temporary history.txt> -NBegin -1 -ReportFormat txt`.
Official manual: https://kb.1ci.com/1C_Enterprise_Platform/Guides/Administrator_Guides/1C_Enterprise_8.3.27_Administrator_Guide/Appendix_7._Startup_command-line_options_of_1C_Enterprise/7.4._Running_Designer_in_batch_mode/7.4.15._Configuration_repository_operations/

The existing repository-connected intermediary is used via `/F` or
`/S`, with optional existing IB login `/N`, `/P`. We do not bind a new
database, modify EDT project files or copy repository credentials into logs.

Because the exact TXT report serialization depends on platform version and
compatibility mode, the first implementation ONLY accepts exactly one
standalone heading `Версия: 29397` or `Version: 29397`, using the
already established BOM-aware strict TXT decoder. A missing heading,
invalid encoding, duplicate/ambiguous headings or native report failure
leave the revision **unverified**. In particular, do not parse integers
from object `configVersion` tokens, from stdout, from arbitrary comments
or from an assumed increment of the previously known revision.

No real native TXT report from this particular platform has yet been
provided, so the parser's compatibility is **not proven**. Unit fixtures
are illustrative, not a substitute for an authentic report.

## When the markers advance

- The observed latest revision is read after the post-update
  `ConfigDumpInfo.xml` snapshot and stored separately from the branch.
- A FULLY applied revision can advance only after a **fresh** Designer
  update for which the repository's latest revision is unchanged both
  before and after the update, its staged XML comparison succeeds, the
  editor closes, Git branch/HEAD is unchanged, all pending object identities
  are cleared by verifying actual EDT file trees against staging, AND
  one more native latest-version query still matches the staged version.
- A retry of an earlier pending-only stage, a crash recovery, partial
  acceptance, uncertain native report or a new concurrent repository revision
  cannot advance the applied marker.
- The applied checkpoint is scoped by EDT project, repository address,
  intermediary identity **and active Git branch**. Reading the settings
  screen does not require a clean Git worktree.
- Neither marker makes `ConfigDumpInfo.xml` an authoritative repository
  revision report. This is additional provenance data, not a rewrite
  of the existing object-based incremental detection.

## Important limitations

- The format of the real platform's latest-version TXT report must be
  checked; absent a matching heading the fields remain "Не определена".
- A stable repository latest revision across the Designer update is a
  necessary safeguard but **not absolute proof** of the exact revision
  downloaded to the intermediary. No API currently certifies a signed
  correlation of intermediary configuration content and repository HEAD.
- The current strict equality verifier can leave even successfully merged
  objects pending if they are not byte-for-byte identical; this prevents
  false claims but may prevent applied version from advancing.
- There is no three-way merge common ancestor, and no support for root
  changes in later selective receives; those stay pending.
- Preference keys from older unverified global `StorageProjectSettings`
  revision placeholders are not migrated or treated as trusted revisions.
- The version fields refresh when the settings window is reopened after
  a receive; they do not live-update in a still-open dialog.
- On Windows the native Designer's `/P` switch, like the existing base
  update command, may expose the IB password to local process inspectors;
  no password is written to persistent plugin logs.
- There has been **NO** Maven/CI build, repository contact, or live test
  of these changes. Do not claim the new version fields already work
  in EDT before installing an explicitly authorized validated build.
