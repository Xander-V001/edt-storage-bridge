# ADR 0004 — Minimal Git workflow and selective repository receive

Status: architectural decision accepted; **comparison/staging not implemented**
Date: 2026-10-16

## Branches: simple, developer-controlled only

- `main` — primary branch.
- `feature/<task>` — one branch for each task, created from `main`, containing task commits.
- **No** automatically created `storage-sync` branch, technical Git branch, worktree, hidden Git merge or helper commits.
- The Git repository of the EDT configuration project is separate from the GitHub repository of this plugin.

## Receive: stage → compare → select → apply

1. Check actual classic storage version and the user's current project/branch.
2. Collect new repository changes into an **isolated temporary staging area** (e.g. a validated temporary export/model; if platform operations require a staging infobase, it must be explicitly approved). The *current EDT project / `main` / feature branch are not modified at this stage*. This is not Git and not visible as a branch.
3. **Always** open an EDT object/property-level **comparison and merge view**, with selections proposed by default for incoming-only changes, and preserved local changes for diverging objects. An object may be accepted, skipped or manually merged. Object dependencies, deletes and metadata model consistency must be validated.
4. Apply only approved changes to the active EDT project. Developer decides when/how to commit them in normal local Git.
5. If an object (e.g. Document1) was changed locally and also remotely, keeping it local means its remote change stays **pending/deferred**, *not* silently resolved. Track object identity, source revision and skipped status in plugin state.
6. Never advance a **whole-project last-applied revision** to 101 when Document1's remote changes from 101 were skipped. Partial adoption requires explicit per-object state and a verified overall baseline for future comparisons.
7. On next receive/push, revisit pending objects, display their differences, prevent overwriting remote object changes and reconcile before sending overlapping work.

**Critical implementation proof:** identify an EDT 2026.2 supported compare/merge API that accepts a staged configuration and existing EDT project and allows object-level selection (including added/deleted objects and dependent metadata). The current direct two-stage import **does not** provide this mandatory pre-apply selection and is **NOT production-ready**. Fail closed instead of using a blind import.

## Send selected task commits to classic storage

1. Work in a named `feature/<task>` branch; commit task changes. Merge them to `main` at the developer's discretion.
2. Show which **unpublished contiguous Git commits** to publish and their derived affected 1C metadata objects. No arbitrary pick without dependency checks.
3. Verify classical repository revision, actual object changes/locks, current Git branch/HEAD and any pending skipped remote changes. If affected objects diverged, require receive/compare/reconcile first.
4. Through the connected infobase and only safe tested Designer operations, stage/apply/capture and commit the intended objects. Do not overwrite unrelated changes or the developer's working base.
5. Verify real final repository version and objects. Only then persist mapping of Git commit SHAs to actual classical repository revision(s). A Git commit is **never** itself a storage revision; mapping need not be 1:1.

## Bookkeeping

- Keep separate states for classic storage version, actual EDT project branch/HEAD and Git commits.
- Persist sync state by project + classical storage identity + branch; add per-object skipped/accepted/pending records.
- All receive/write operations require explicit developer action. Never alter another user's live infobase or VPS without their permission.
- No automatic CI build. Initial implementation/test exclusively against disposable fixtures.
