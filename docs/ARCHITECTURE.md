# EDT Storage Bridge — architecture (EDT 2026.2)

## Components

- `core`: validations, immutable domain and future transfer plan, no UI/EDT/1C calls.
- `edt`: public EDT OSGi/Guice service adapter for read-only model probing, later verified import/export APIs.
- `ui`: project context command, form, workspace instance preferences, encrypted password, independent remote/applied revision labels.
- **Read-only repository adapter (planned):** choose either direct documented transport or 1C Designer history report, based on actual capabilities.
- **Designer adapter (planned):** optional use of a **pre-existing connected infobase** to perform platform-supported repository operations. No rebinding/rewriting/mutating on form save. Strict side-effect safeguards.
- **Git adapter (optional):** independent branch/commit state, not a classical storage revision.

## Important identities

Each project may have a repository address, repository user/secret, an *optional* connected-base location/mode (`/F` or `/S`), an *optional* `1cv8.exe` path, and two distinct revision counters.

- `observedRemoteVersion`: remote repository version actually read and authenticated.
- `lastVerifiedAppliedVersion`: last repository version confirmed in EDT after safe synchronization.

Never increment either speculatively; changing repository identity invalidates both. Base connection is not an EDT checkpoint.

## Operations (design, not yet implemented)

1. **Preflight:** validate workspace/selected project, 1C platform version, repository identity, connected infobase mode/identity, auth availability and captured objects. Validate that repository user is compatible with the already bound base. Do not bind to a different base to 'repair' access.
2. **Discover:** fetch remote version/history without writing or modifying configuration. Separate report generation from actual receive.
3. **Plan:** compare only objects that actually changed since the verified applied checkpoint, detect divergent EDT edits and locks. Handle addition, deletion and references.
4. **Receive:** use public EDT APIs and supported Designer commands if and only if platform semantics require the intermediary connected base; isolate/backup and require explicit user confirmation for any base mutation.
5. **Verify:** verify resulting EDT objects and external storage state; only then advance applied checkpoint.
6. **Commit/send:** perform object capture and repository commit with read-after-write verification; uncertain results are not silently retried.
7. **Recovery:** preserve an operation journal, safely report incomplete phases without false success.

**Constraints:** project-context-only settings, optional connected base, no unapproved production base changes, no secrets in logs, no automatic builds. Prefer reliable supported platform mechanics over fragile direct binary repository writes.
