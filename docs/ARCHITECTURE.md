# EDT Storage Bridge — architecture (EDT 2026.2)

## Components

- `core`: validations, immutable domain and future transfer plan, no UI/EDT/1C calls.
- `edt`: public EDT OSGi/Guice service adapter for read-only model probing, later verified import/export APIs.
- `ui`: project context command, form, workspace instance preferences, encrypted password, independent remote/applied revision labels.
- **Read-only repository adapter (planned):** choose either direct documented transport or 1C Designer history report, based on actual capabilities.
- **Designer adapter (planned):** optional use of a **pre-existing connected infobase** to perform platform-supported repository operations. No rebinding/rewriting/mutating on form save. Strict side-effect safeguards.
- **Local Git adapter (required for task workflow, planned):** inspect active branch, commit graph, working-tree status, candidate commits, object-level diffs and mappings to confirmed classical-repository revisions. No implicit reset/rebase/checkout or auto-commit to user's task branch.
- **Isolated receive staging (planned):** ephemeral files/model and, where required, a disposable test/staging infobase for collecting and comparing repository changes. **No extra Git branch, no Git worktree and no auto-commit.** The source of truth for developers remains `main` plus one feature branch per task.

## Important identities

**One task = one feature branch** (e.g. `feature/TASK-123`), with an arbitrary number of local commits. The plugin's own source repository is unrelated to the local Git repository of an EDT configuration project.

Each project may have a repository address, repository user/secret, an *optional* connected-base location/mode (`/F` or `/S`), an *optional* `1cv8.exe` path, and two distinct revision counters.

- `observedRemoteVersion`: remote repository version actually read and authenticated.
- `lastVerifiedAppliedVersion`: last repository version confirmed in EDT after safe synchronization.

**Important schema gap:** the current `lastVerifiedAppliedVersion` field is keyed only by project path. Before enabling real sync, migrate to **(project, classical repository identity, Git branch)** checkpoints with a Git baseline SHA plus **per-object applied/skipped/pending state**. If a developer omits Document1 but imports Document2 from storage version 101, do not claim every object in project matches version 101. A Git SHA is not a classical repository revision.

Never increment either speculatively; changing repository identity invalidates both. Base connection is not an EDT checkpoint.

## Operations (design, not yet implemented)

1. **Preflight:** validate workspace/selected project, 1C platform version, repository identity, connected infobase mode/identity, auth availability and captured objects. Validate that repository user is compatible with the already bound base. Do not bind to a different base to 'repair' access.
2. **Discover:** fetch remote version/history without writing or modifying configuration. Separate report generation from actual receive.
3. **Plan:** compare only objects that actually changed since the verified applied checkpoint, detect divergent EDT edits and locks. Handle addition, deletion and references.
4. **Receive:** collect repository changes into isolated staging, **always display an EDT compare/merge preview** with object-level selection, then apply only explicitly approved items into the currently active user branch. No technical Git branches. Ensure base/dependency consistency; if an EDT API cannot preview safely, stop rather than blindly import. Isolate/backup and require user confirmation for any base mutation.
5. **Verify:** compare selected objects against the staged version, record applied and deferred objects. Advance a full-version checkpoint only if **every required change** has been applied and verified. Otherwise keep per-object pending differences and do not assert full synchronization.
6. **Commit/send:** perform object capture and repository commit with read-after-write verification; uncertain results are not silently retried.
7. **Local Git task flow:** show uncommitted changes, current task branch, selected untransferred commit range, objects affected and dependency/conflict checks. Only contiguous, validated unpublished commit ranges in v1; arbitrary cherry-pick subset requires separate proof.
8. **Recovery:** preserve an operation journal, safely report incomplete phases without false success.

**Constraints:** project-context-only settings, optional connected base, no unapproved production base changes, no secrets in logs, no automatic builds. Prefer reliable supported platform mechanics over fragile direct binary repository writes.
