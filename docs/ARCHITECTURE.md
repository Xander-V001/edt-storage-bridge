# Architecture

- **core:** immutable state, validation, future diff/plan engine; no Eclipse UI, Git or 1C transport.
- **edt:** public EDT Guice/OSGi service adapter; does not access the classic 1C repository.
- **ui:** project-context command, SWT/JFace form, workspace-scoped preferences, Eclipse Secure Storage, background Jobs.
- **direct-read repository adapter (planned):** authenticated metadata, current version and changed-object history, strictly read-only.
- **platform-backed adapter (planned, optional):** isolated connected 1C infobase for Designer-dependent capture/commit operations, only if necessary.
- **Git adapter (optional):** handles EDT source history separately, never substitutes for repository verification.

## Version invariants

- `remoteObservedVersion`: latest number actually read from the classical repository.
- `lastVerifiedAppliedVersion`: latest repository snapshot truly reflected in the EDT workspace after verified synchronization.
- **No guessed increments.** Failure, canceled operation, partial import, Git commit, or successful Designer exit alone must never advance `lastVerifiedAppliedVersion`.
- Changing repository address invalidates both stored versions.

## Proposed safe synchronization

1. Validate current EDT project, repository address/credentials, workspace modifications and storage identity.
2. Discover remote history/version via read-only transport where supported.
3. Calculate changed objects and conflicts vs confirmed applied version.
4. Prepare reversible staged state; only use optional connected infobase if required by a proven supported platform operation.
5. Import/merge safely into EDT using public APIs, retaining local modifications.
6. Read target EDT model / repository state to verify success.
7. Update the confirmed project checkpoint only after verification; retain detailed journal for interrupted/uncertain operations.

For sending changes, support and test capture/commit semantics separately, with read-after-write verification of the **actual** classical repository, not only an intermediary DB.

No production base/repository writes, secret leakage, unexplained reflection or automatic builds.
