# Receive changes — connected 1C storage → infobase → EDT existing project

**UNSAFE FOR WORKING BASES: Prototype source has NOT been compiled or tested in EDT 2026.2 and does not support obligatory pre-apply compare/merge or object-level selection. Use ONLY disposable fixtures after an explicitly approved build.**

## User action (new)

Right click the EDT project → **Работа с хранилищем** → fill repository address, account, already-connected infobase (/F or /S), installed `1cv8.exe` → **Получить изменения**.

The button saves project-specific settings, opens the *official EDT infobase selection dialog* and requires user confirmation that the selected registered EDT infobase is **the SAME physical infobase** as the configured file/server address. This identity check is **not yet automatic** and is a release blocker for unattended use.

Then the user must approve a prominent warning: the connected infobase configuration will be modified. There are no background/scheduled calls to receive.

## Implemented vertical slice

1. **Git preflight**: Git executable must be accessible; a non-detached branch with a valid HEAD must be checked out and the entire Git worktree must be clean (including untracked files). No Git reset/checkout/commit/rebase.
2. **EDT preflight**: `IInfobaseSynchronizationStateManager.hasSynchronizationInfo(project, selectedIB)` must be true; reject if the synchronization flow is already active. This ensures an EDT baseline exists. A base only connected to the *classic repository*, and not already associated/synchronized with this EDT project, is NOT sufficient.
3. **Designer receive into the configured base**: `1cv8.exe DESIGNER /F|/S <ib> /DisableStartupDialogs /ConfigurationRepositoryUpdateCfg -force /Out <temp-log>`. Source is an existing repository-connected base; platform manages its association. The `-force` option enables receipt of newly added/deleted objects. `-revised` is ALWAYS absent so current user captures are not deliberately overwritten. This is a **mutating action**. The operator must independently ensure the connected base does not contain unsaved/ongoing Designer work before approving.
4. **Bounded command runner**: ProcessBuilder without shell or passwords in argv; timeout 20 minutes by caller (10 seconds to 30 minutes accepted by request); user cancellation stops spawned Designer; redirect stdout/stderr; capped diagnostic logs and temp cleanup.
5. **Git preflight again**: reject if branch/HEAD changed or local uncommitted files appeared during stage 3.
6. **EDT public synchronization API**: `IInfobaseSynchronizationManager.retrieveInfobaseChanges(project, ib, callback, false, monitor)` with the verified original EDT state. Callback aborts/defer on divergent EDT added/modified objects. Otherwise delegates to platform-provided `IInfobaseUpdateConflictResolver.resolveConflict(...)`, which uses EDT import/merge machinery. Neither `updateInfobase` nor `reloadInfobase` is called, so this is **IB → project** not a reverse push.
7. **Outcome**: `CHANGES_RESOLVED` / `NO_CHANGES` are presented as the *EDT stage's* result, not yet as confirmed full classic repository synchronization. Deferred/ignored/unknown are not reported as success. No version checkpoint is updated by this action; actual repository version and object reconciliation require later verification.

## Known blockers/limits — do not hide them

- Public API signatures were verified from 1C EDT **2025.2** Javadocs. Actual installed **2026.2** binary compilation/runtime must still be checked by a user-requested manual CI run and isolated EDT install.
- The selected EDT infobase is currently confirmed by the user, **not automatically compared** to configured /F or /S location. A wrong choice could receive one infobase into another project's EDT model.
- Original attached base might contain captured objects or work. Code does not yet inspect base capture state, acquire an exclusive Designer lock or guarantee that no other process updates the base concurrently. **Only a disposable connected base can be used for acceptance tests.**
- No 1C infobase username/password is currently passed (and repository credentials are intentionally not placed on command line), so Designer batch operations will fail when local environment requires interactive login. Never add /P with plaintext passwords merely to make tests pass.
- EDT project MUST be already synchronized with selected infobase, not just with the classic repository. No automatic association is attempted (association may initiate unwanted synchronization).
- Conflicts with EDT local model changes lead to **deferred** outcome and must be resolved manually later. There is no automated conflict merge for diverged task branches.
- The last applied classic repository version is NOT advanced. Exit code 0 is not proof of exact version after concurrent commits, and EDT success is not proof of expected object list. Future state checkpoint must be per Git branch with verified version/SHA.
- The existing permanently connected user infobase is **not** authorized for direct execution by implementing this code. Source-only changes cannot affect the base until the operator explicitly installs, configures and clicks the command.

## Superseding user experience decision

**No automatically created Git branches.** Only `main` and task `feature/...` branches, all developer-managed.

Receiving new storage data must first produce an **isolated non-Git staging snapshot**, show an **always-on comparison/merge view**, and let the developer accept, skip or manually merge individual changed metadata objects. Applying accepted objects to the current project must not alter skipped objects. Skipped remote changes remain pending for later receive/push; full-project revision checkpoint is not advanced on partial selection.

The existing two-stage prototype directly calls EDT import into the active project after base update; it **does not fulfill this requirement**, even if Git worktree is clean. It must be replaced/guarded before use on working bases. A production receive button must stay blocked until that mandatory compare feature has been verified in EDT 2026.2.

## Future safety hardening

1. Compile once only on explicit request; fix Tycho/API regressions.
2. Isolated EDT 2026.2 install and smoke test with a disposable connected repository and infobase.
3. Mechanically compare selected EDT infobase address/UUID to configured base, guard captures and concurrent sessions, and verify the already-connected base belongs to the intended classic repository.
4. Test no local modifications, local divergent commits, additions/deletions, cancellations and partial failures.
5. Verify actual repository revision and per-branch Git baseline before persisting the applied checkpoint.
6. Later permit UI-assisted conflict resolution via EDT's official comparison editor, never silent overwrite.
