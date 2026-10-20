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

## 2026-10-17: Follow-up on missing version fields after user took everything on the right

Code added to this branch (source only, unbuilt/unverified in EDT 2026.2):

- `RepositoryLatestRevisionReader.parseLatest(byte[])` accepts the native
  strictly validated Russian history report in UTF-8, UTF-8 BOM, UTF-16LE
  (including BOM-less) and Windows-1251. Non-BOM encodings are tried only if
  the WHOLE report passes `RepositoryVersionReportParser` checks; merely
  finding digits or a random `Версия:` is insufficient. Manual GUI-exported
  TXT and Designer batch-created TXT may differ in encoding. Tests have been
  added for each accepted byte format; real batch output remains unverified.
- After closing the EDT comparison editor, `VerifiedReceiveContent.verifyAndClear`
  reports counts of owners checked, owners **byte-identical** to the external
  EDT stage, pending identities cleared and pending identities left.
  It writes a local `edt-storage-merge-review-*.txt` ONLY when something
  remained unverified; for up to 500 owners it identifies the first
  non-matching resource. This diagnostic is potentially confidential.
- `ReceiveChangesCoordinator` now presents that summary after the editor
  closes instead of silently returning from the background verification job.
  It also reports native history re-query failure or changed revision.
  It only records an applied checkpoint if EVERY selected owner matches, the
  pending journal is EMPTY, Git branch/HEAD is stable, and the native version
  remains unchanged. This is stricter than believing the editor's checked
  `Взять справа` options.
- The independent «Проверить версию хранилища» command remains the quickest
  method of testing native last-revision extraction WITHOUT another full XML
  export.

**Unresolved**: A byte-level mismatch is not necessarily a semantic
difference in EDT's in-memory model. Conversely, treating differences as
accepted merely because the user chose `Взять справа` risks real loss of
forms, command mappings and manual conflict resolutions. A future safe
semantic acceptance proof must use a **fresh completed native EDT comparison
of the resulting project with the retained incoming stage**, inspect its
actual model differences, and verify no outstanding conflicts. The EDT
public `IComparisonManager`/`IComparisonSession` services are candidates,
but their 2026.2 signatures and event/session lifecycle must be tested
before wiring them into the active receive path. The current release keeps
the applied checkpoint UNKNOWN instead of inventing proof.

## 2026-10-17 follow-up: standalone latest revision parsing fix

A manual 'Проверить версию хранилища' still returned the generic message
'Отчёт не удалось прочитать или распознать'. The native command's process,
nonzero exit and missing-report cases have separate error handling, so
this points to an IOException after running or reading the report; however
the **exact batch TXT bytes were not captured** and root-cause attribution
to the old parser cannot be proven yet.

Definite implementation defect: `RepositoryLatestRevisionReader.parseLatest`
delegated to `RepositoryVersionReportParser.latest`. The detailed history
parser requires every version's comments and all declared object lists to
be recognized and counted. That is unnecessary for retrieving the latest
repository revision and can reject valid TXT reports with different detail
layout. The standalone reader now validates the report title and each
complete version metadata header (number, configuration version, user,
creation date and time). It NEVER infers a version from ConfigDumpInfo,
arbitrary numbers in comments, or a successful Designer exit. Last revision
is the greatest nonduplicate native header in the report. The detailed
history parser is still strict when object listings are actually needed.

The explicit read-only `/ConfigurationRepositoryReport -NBegin -1
-ReportFormat txt` query remains unchanged; this is the documented native
latest-version request. Both UTF-8 and UTF-16 (BOM and selected BOM-less
variants) and Windows-1251 are supported after strict textual validation.

If the batch report still cannot be parsed, a *local*, raw TXT copy is
preserved in the OS temporary directory as
`edt-storage-version-report-*.txt`, and the UI shows a categorized failure
and its path. The report can reveal server addresses, usernames, comments
and object names: review/redact it before sharing, never commit it to Git.
No raw report bytes or credentials are sent to the Eclipse log.

New tests distinguish valid latest-version extraction with omitted or
malformed detailed sections from strict history parsing; they also reject
faked 'Версия' lines without accompanying metadata and duplicate headers.
**Not built or tested in installed EDT 2026.2.** The CI workflow remains
manual-only, requiring explicit approval. Do not raise the fully-applied
EDT checkpoint merely because a latest version number was read.

## 2026-10-20: full-inventory count and repository HEAD advancing during EDT merge

Runtime observed in EDT 2026.2 after a global comparison of native snapshot
**29432**: the repository HEAD had advanced to **29434** during the long EDT
merge. The result window reported **23,408 pending identities** and zero
cleared items. This is **not** 23,408 freshly changed objects: the first
or explicit full review stores the entire native `ConfigDumpInfo.xml` name
inventory as pending until actual EDT acceptance is verified.

Source-level corrective behavior (NOT YET built/tested in EDT):

- Never infer that a finished EDT merge means that the later HEAD 29434 was
  merged. The staged XML/EDT source belongs only to previously attested 29432.
- When a later positive HEAD appears, verify that the saved native 29432
  attestation still matches the **same** last-scanned ConfigDumpInfo digest,
  and recapture the existing intermediary's read-only inventory to prove it
  unchanged. Only then may the normal post-merge audit and explicit review
  proceed against **29432**, never against 29434.
- The `REVIEWED` checkpoint may therefore lag behind `OBSERVED`; this
  does not update `APPLIED` or claim that new revisions were received.
  Version 29433/29434 must be discovered on the next native incremental run.
- A pending-only retry first verifies that the intermediary has the
  exact saved native inventory. It reuses the **previously attested** snapshot
  revision even when the newly observed HEAD is greater; it must not
  reinterpret the old XML as the new HEAD or re-run the repository update.
- Missing/mismatched inventory proof, repository reset or user rejection
  still leaves remote objects pending. Nothing is forcibly cleared.
- Large full-review inventories are now described as *journal identities*
  rather than new changes. The dialog shows at most five example names.
  Verification groups pending identities by EDT owner once to avoid
  quadratic repeated name-to-owner conversion.

Known limitation: a previous already-closed EDT comparison does not retain
its full native staging source, so an unfinished review cannot be certified
retroactively from the modal result alone. A new controlled comparison may
be required. There is still no proof from an installed EDT of the newly
changed code. CI builds must be requested explicitly.
