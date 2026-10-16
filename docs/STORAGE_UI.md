# UI workflow for classical configuration repository

Status: source implemented in PR #6, **not yet compiled or installed**.
Target: 1C:EDT **2026.2**.

## User interaction

Right-click a project in EDT Navigator or another Eclipse project selection view:
`Работа с хранилищем → Настройки и состояние`.

The dialog opens for that specific Eclipse `IProject`. Fields:
- repository address/path (local path, TCP or HTTP; transport not implemented yet);
- repository username;
- repository password (masked SWT input);
- last observed remote storage version;
- last successfully applied storage version in the EDT project.

Saving writes address/login and version checkpoints to **workspace instance preferences** keyed by SHA-256 of the project location URI. It does NOT create files in project directories and cannot accidentally commit credentials to Git. The password is encrypted using **Eclipse Secure Storage**, stored separately. Saving aborts on encryption failure. A new repository address invalidates cached versions to prevent confusing different repositories. Username/password changes do not silently change versions.

The address must be nonblank. No network test occurs during saving. The settings dialog is intentionally only a form; no unimplemented fetch, push or test button is active. This avoids an apparent false-success UI.

## Two version values: do not conflate

1. **Remote current version**: last actually observed from the repository by a future read-only transport; it may increase due to other team members. The form currently displays 'Не определена'.
2. **Applied project checkpoint**: the actual repository revision confirmed as applied to EDT model. It must not be changed by entering settings, reading a remote version, Git checkout or a subprocess exit code alone. The form currently displays 'Не определена'.

`StorageProjectSettings.recordObservedRemoteVersion()` and
`StorageProjectSettings.recordVerifiedAppliedVersion()` are **future coordinator hooks**, not called by UI. They must be invoked only after their respective transport and verification success. Applied revisions are actual repository revision numbers; never calculate them as previous+1.

## Why an intermediate 1C infobase is not an assumption

- An existing plugin (ZigRinat85/PluginEDT) describes using a repository-connected 1C infobase for locking, receiving and committing, then importing to EDT: https://infostart.ru/1c/articles/2442956/
- gitsync/v8storage documents direct repository read and export, and a persisted `VERSION` value: https://github.com/oscript-library/gitsync and https://github.com/oscript-library/v8storage
- Platform DESIGNER update and repository commit commands are typically executed in an infobase context; direct read-only metadata and full bidirectional write have different requirements.
- Architectural decision: prefer a **direct read-only adapter** for remote version/history. Prototype and benchmark safe write mechanisms. An isolated connected infobase is an **optional transport adapter only where required by the platform**. Never use a user's production infobase without explicit instruction.

## Acceptance tests after next user-approved build

1. Confirm project-context menu appears in the **actual 1C:EDT 2026.2 Navigator** (not only Eclipse Project Explorer).
2. Open the settings dialog and save address/login/password; close and reopen to confirm persistence.
3. Check that password is not visible in plain workspace or project files and remains masked in UI.
4. Verify two unknown version labels before any genuine repository contact.
5. Change repository address and check both version checkpoints are invalidated.
6. Close/rename/reopen project; check behavior and record any project location identity changes.
7. Test failure or locked Eclipse Secure Storage; no secret must fall back to plain settings.
8. Install ZIP in isolated EDT; no repository writes, platform commands, or Git changes occur.
