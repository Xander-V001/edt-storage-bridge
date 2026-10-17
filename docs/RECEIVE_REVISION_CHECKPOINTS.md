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
`/S`, with optional existing IB login `/N`, `/P`. The report explicitly
receives the **same** `/ConfigurationRepositoryF/N/P` source and credentials
as the existing update command, so it cannot silently report the latest
revision of a different repository bound to the intermediary. We do not bind
a new database, modify EDT project files or copy credentials into logs.

**Authentic report fixture inspected 2026-10-17.** The user supplied a
native UTF-8-with-BOM Russian `Отчет по версиям хранилища` TXT file.
It contains five chronological revision blocks, `29400` through
`29404`. Each has a `Версия:\t<number>` heading (distinct from
`Версия конфигурации: 5.0.113.3`), author, date, time,
`Комментарий:` followed by comment lines, and optionally
`Добавлены/Изменены/Удалены\t<count>` sections with tab-indented
metadata object names. Actual counters reconcile to
8 added, 5659 modified and 94 deleted entries across the five revisions.

`RepositoryVersionReportParser` is now a **source-only implementation**
based on this exact structure. It validates header, strictly increasing
repository revisions, required fields, parseable timestamps, each
section's stated count, and every expected metadata name. It exposes
immutable entries with revision number, user, date, original comment,
and 1C object names. `RepositoryLatestRevisionReader` uses the last
validated block; a native `-NBegin -1` query should request the latest
block, whereas `-NBegin X -NEnd Y` can return several. Invalid reports
leave the field **unverified**; object configVersion tokens and arbitrary
text numbers are NEVER used as a fallback.

The authentic report is not committed because it includes private 1C
connection and user data. The codebase contains synthetic anonymized
JUnit regression cases with its observed exact structure. No tests
or EDT build have been executed after these changes.

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

- A real manual TXT report format is now understood, but the exact
  **batch** report of `/ConfigurationRepositoryReport -NBegin -1`
  remains untested on this host. On auth/report/format mismatch the
  fields remain "Не определена".
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
- On Windows the native Designer's `/P` and
  `/ConfigurationRepositoryP` switches, like the existing base update
  command, may expose passwords to local process inspectors; no
  password is written to persistent plugin logs.
- There has been **NO** Maven/CI build, repository contact, or live test
  of these changes. Do not claim the new version fields already work
  in EDT before installing an explicitly authorized validated build.

## Git commits from native storage comments

The parsed version/date/author/comment/added/modified/deleted data can
be used for a future **proposed Git commit message**, but the current plugin
does not create Git commits. Neither a repository history report nor
the successful Designer update proves that the user accepted all changes
in the EDT merge editor. A commit must stage only verified resulting
Git files from the accepted change-set, after explicit consent, and
must never include unrelated local changes or unaccepted remote objects.

On a receive covering e.g. revisions 100 to 104, request a range
101..104 for commentary (not -NBegin -1). Keep the original per-revision
comments; list only actual accepted objects in the commit body, and
separately identify skipped/unresolved revisions. A partial merge must
never claim that all of 104 was applied. One Git commit per confirmed
receive operation avoids fabricating unavailable intermediate EDT states.
