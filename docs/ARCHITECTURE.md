# EDT Storage Bridge — architecture (EDT 2026.2)

## Components

- `core`: validations, immutable domain and future transfer plan, no UI/EDT/1C calls.
- `edt`: public EDT OSGi/Guice service adapter for read-only model probing, later verified import/export APIs.
- `ui`: project context command, form, workspace instance preferences, encrypted password, independent remote/applied revision labels.
- **Read-only repository adapter (planned):** choose either direct documented transport or 1C Designer history report, based on actual capabilities.
- **Designer adapter (planned):** optional use of a **pre-existing connected infobase** to perform platform-supported repository operations. No rebinding/rewriting/mutating on form save. Strict side-effect safeguards.
- **Local Git adapter (required for task workflow, planned):** inspect active branch, commit graph, working-tree status, candidate commits, object-level diffs and mappings to confirmed classical-repository revisions. No implicit reset/rebase/checkout or auto-commit to user's task branch.
- **Storage baseline Git branch (planned):** one *technical* mirror of confirmed storage state, e.g. `storage-sync` in an isolated Git worktree, never checked out over the active EDT project. It is not a user task branch. Updating the active feature branch always requires a conflict-aware, user-approved integration.

## Important identities

**One task = one feature branch** (e.g. `feature/TASK-123`), with an arbitrary number of local commits. The plugin's own source repository is unrelated to the local Git repository of an EDT configuration project.

Each project may have a repository address, repository user/secret, an *optional* connected-base location/mode (`/F` or `/S`), an *optional* `1cv8.exe` path, and two distinct revision counters.

- `observedRemoteVersion`: remote repository version actually read and authenticated.
- `lastVerifiedAppliedVersion`: last repository version confirmed in EDT after safe synchronization.

**Important schema gap:** the current `lastVerifiedAppliedVersion` field is keyed only by project path. That is inadequate when several feature branches alternate in the same EDT project. Before enabling real sync, migrate to **(project, classical repository identity, Git branch)** checkpoints containing the remote version *and* immutable Git baseline SHA, and reject switches to unrelated branches with uncertain baseline. A Git SHA is never itself a classical repository revision.

Never increment either speculatively; changing repository identity invalidates both. Base connection is not an EDT checkpoint.

## Operations (design, not yet implemented)

1. **Preflight:** validate workspace/selected project, 1C platform version, repository identity, connected infobase mode/identity, auth availability and captured objects. Validate that repository user is compatible with the already bound base. Do not bind to a different base to 'repair' access.
2. **Discover:** fetch remote version/history without writing or modifying configuration. Separate report generation from actual receive.
3. **Plan:** compare only objects that actually changed since the verified applied checkpoint, detect divergent EDT edits and locks. Handle addition, deletion and references.
4. **Receive:** use public EDT APIs and supported Designer commands if and only if platform semantics require the intermediary connected base; isolate/backup and require explicit user confirmation for any base mutation.
5. **Verify:** verify resulting EDT objects and external storage state; only then advance applied checkpoint.
6. **Commit/send:** perform object capture and repository commit with read-after-write verification; uncertain results are not silently retried.
7. **Local Git task flow:** show uncommitted changes, current task branch, selected untransferred commit range, objects affected and dependency/conflict checks. Only contiguous, validated unpublished commit ranges in v1; arbitrary cherry-pick subset requires separate proof.
8. **Recovery:** preserve an operation journal, safely report incomplete phases without false success.

**Constraints:** project-context-only settings, optional connected base, no unapproved production base changes, no secrets in logs, no automatic builds. Prefer reliable supported platform mechanics over fragile direct binary repository writes.
