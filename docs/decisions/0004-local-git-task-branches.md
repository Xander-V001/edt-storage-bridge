# ADR 0004 — Local Git task branch workflow and transfer to classic 1C storage

Status: accepted architectural requirement; **not implemented**
Date: 2026-10-16

## User requirements

- Each task is developed in **one local Git feature branch** of the EDT *configuration project* (`feature/TASK-123`).
- A task branch may contain many commits.
- The user must be able to choose committed task changes for subsequent placement into the classic 1C configuration repository.
- Incoming repository changes must not erase local work, even on a task branch.
- Existing connected infobase remains a candidate for supported 1C Designer operations.
- The local Git repository is NOT the GitHub repository where this plugin is developed.

## Three distinct histories

1. Local Git: branch name, immutable commit SHA, parents and file changes.
2. Classical storage: repository identifier, numeric revision, committed objects, locks and authors.
3. EDT: actual current configuration model and potentially uncommitted workspace changes.

Store a **per-project, per-classical-storage, per-Git-branch** sync checkpoint comprising verified storage revision and corresponding Git baseline SHA. The existing project-only saved applied version must be migrated before any real receive/commit command is enabled. Do not misreport the same checkpoint after switching task branches.

## Receive (storage → task)

1. Verify active Git branch, repository identity, clean or explicitly preserved uncommitted workspace, and recorded baseline.
2. Read the real storage revision and changed object list. Receive into the already connected base using supported 1C mechanisms; this step is mutating and requires consent.
3. Import changes from the base to an isolated EDT model / technical `storage-sync` worktree, or invoke a verified safe EDT import path that preserves local edits.
4. Record the verified storage baseline in a technical `storage-sync` Git branch or equivalent immutable commit in an **isolated Git worktree** — never forcibly checkout the task branch.
5. Present conflicts and explicitly integrate the baseline into the current task branch using standard Git merge / EDT conflict workflow. Never force reset, autostash without review or discard uncommitted changes.
6. Update applied storage revision for the task branch ONLY after that branch's EDT project reflects the new baseline and import/merge has been verified. The service mirror can be newer than a developer branch.

## Send (selected task commits → storage)

1. Identify selected **unpublished contiguous** Git commits on the task branch relative to the verified baseline SHA; show commit titles and affected 1C metadata objects. This is **not** an unconditional entire-HEAD export.
2. Validate ancestry, active branch, dirty worktree, mapping of EDT file diffs to metadata objects, additions/deletions/references, and that the selected range contains no remote-sync / previously published changes. Reject ambiguous selection and unsupported changes instead of guessing.
3. Re-read actual storage version and captured-object state. If storage advanced, receive and merge first; never force-push or silently overwrite.
4. Export the cumulative selected object state into a **safe staging configuration**. Use existing connected infobase + documented Designer operations ONLY after safety preflight and explicit user approval; do not overwrite the connected base's developer work.
5. Apply/capture/commit the objects to classic 1C storage, inspect real final repository objects and version, and record a durable operation journal. One selected range can produce one or several repository revisions; the mapping is not 1:1.
6. Store a verified mapping `(repository identity, revision(s)) ↔ (branch, selected commit SHAs / object set)`. Mark **published** only after confirmed write. If failure or uncertain result occurs, preserve the pending state for reconciliation; never retry a potentially completed commit automatically.

## Implementation sequence (proving the hardest dependency first)

A. Verify actual EDT 2026.2 import/merge integration on disposable config with a task branch carrying local Git commits.
B. Build read-only local Git adapter to detect active branch, diff and candidate commits, and per-branch checkpoint; demonstrate accurate preview without touching the working tree.
C. Integrate storage revision received into an existing EDT task branch, with confirmed conflict behavior.
D. Implement 'Select Git commits to publish' as object-level plan, first for contiguous commits, with preview.
E. Verify test-storage write and mapping of Git commit(s) to actual storage revision(s).
F. Extend safe options and performance based on measured results; never rush arbitrary cherry-pick.

## Non-goals / explicit safety guarantees

- No blind copying .xml over EDT or attempting to store a Git commit object in 1C storage.
- No automatic task-branch checkout, push, reset, rebase, or merge.
- No guarantee that partial sets of commits can be published safely if subsequent commits alter the same 1C objects.
- No new builds automatically on commits; VPS and connected user infobase remain out-of-scope without explicit permission.
