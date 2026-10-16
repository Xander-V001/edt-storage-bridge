# Receive from classic 1C storage into existing EDT project (prototype)

**Source implementation only. Must compile and pass isolated EDT 2026.2 smoke tests before any production use.** No connected user infobase, storage or VPS has been accessed.

## Actual implemented call chain

1. Project → **Работа с хранилищем** → **Получить изменения (тестовый режим)**.
2. Save existing connection settings; select an IB from the official EDT registry.
3. `InfobaseIdentityMatcher`: require an exact physical file-directory match (`FileConnectionString` with `Files.isSameFile`) or an exact normalized server/infobase pair (`ServerConnectionString`). Reject wrong/ambiguous types and unavailable paths.
4. Require EDT already connected to that IB and containing a prior sync baseline (`isConnected`, `hasSynchronizationInfo`), with no active synchronization. Do not silently connect or bind any base.
5. On the SWT UI thread construct EDT's own **`InfobaseUpdateDialogBasedCallback`**, single-use for this receive. This is a public EDT 2025.2 API that uses EDT comparison editor and merge manager. Disable its "override the infobase with EDT changes" choice via `setAllowOverrideConflict(false)`.
6. Show explicit confirmation that Designer will change the connected infobase configuration. After user confirmation, close the settings dialog so it cannot block the compare/merge editor.
7. Require clean named Git task branch and record its HEAD. **No technical Git branches**, resets, branch switching or commits.
8. Designer `/ConfigurationRepositoryUpdateCfg -force` gets the latest classic repository revision into the pre-existing linked IB. `-revised` is forbidden. A private bounded process runner handles timeout, cancel, and output cleanup. No password command-line switches.
9. Confirm that the Git branch/HEAD/worktree stayed unchanged. Call the **public** `IInfobaseSynchronizationManager.retrieveInfobaseChanges(project, infobase, nativeCallback, false, monitor)`. The native EDT callback prompts the user about IB changes and uses its **normal compare/merge editor** for manual object/property selection and conflicts.
10. Treat `CHANGES_NOT_RESOLVED`, `CHANGES_IGNORE`, or `UNKNOWN` as **not applied**, and never advance repository checkpoint based on an EDT return value. `CHANGES_RESOLVED` / `NO_CHANGES` report only the EDT step outcome, not the entire repository state.

## Known gaps, no hidden claims

- All above APIs were examined in **2025.2** public Javadocs; **not yet compiled against or run inside EDT 2026.2**. New GitHub branch changes are not covered by previous successful CI `38944234655`.
- The actual **classic repository identity** of the pre-connected IB is not programmatically inspected yet. The entered repository address is saved but not used to rebind or authenticate Designer; Designer uses whatever repository the pre-connected IB already has.
- Designer batch command has no configured IB/repository password handling yet; when platform prompts for authentication the operation fails safely rather than exposing secrets on a command line.
- There is no standalone non-Git staging snapshot for the classic repository. Instead the official EDT sync API produces and presents changes with its own comparison machinery, which avoids extra branches. Precise ability to **skip one changed object** and safely apply other changed objects, including deletes/dependencies, must be proven in EDT 2026.2.
- A connected infobase may have local Configurator work/captures or concurrent users; this code does **not** inspect or isolate them. Therefore the existing user's working IB is OUT OF SCOPE for acceptance tests. Only a disposable bound IB after explicit permission.
- Incoming changes can be partially deferred by the user. We intentionally never mark a whole storage revision applied. Persisted per-object deferred state and per-branch reconciliation are still missing.
- The earlier simplistic custom conflict resolver was removed. **Do not reintroduce a "give IB priority" automatic import**: the native EDT dialog is required.

## Acceptance checks still needed

- User explicitly requests one manual CI build: fix real 2026.2 Tycho compilation errors if any.
- Install on EDT 2026.2 with disposable configuration repository + disposable connected IB + project, confirm registered-IB identity, merge dialog and project outcome.
- Test locally committed edits to Document1 + remote edits to Document1 and Document2, with Document1 skipped. Ensure Document1 preserved, Document2 applied and deferred object recognized on next operation.
- Repeat for added/deleted objects, dependencies, cancellation, concurrent repository revisions and compare-window lifecycle. Confirm native UI doesn't deadlock.
- Only then consider working IB use and implement per-branch / per-object state checkpoints and safe send-to-storage.
