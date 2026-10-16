# Architecture

## Component boundaries

- **core**: immutable project/synchronization values, deterministic validation logic. No Eclipse UI, no 1C APIs, no Git.
- **edt**: thin facade over the **public** 1C:EDT OSGi API. Current read-only `EdtProjectInspector` uses `IConfigurationProvider`; this bundle must not know about 1C storage command-line transport.
- **ui**: Eclipse workbench, menu commands, background Jobs, user-facing diagnostics, Guice factory.
- **repository adapter (planned)**: 1C platform and repository operations, version-aware subprocess execution and safe read-only discovery first.
- **Git adapter (planned)**: optional; classic 1C repository operations must not depend on Git.

The current feature installs `core`, `edt`, `ui`. None of them writes to configuration repositories. EDT 2026.2 source is not yet compiled or smoke-tested.

## Synchronization lifecycle — design contract, not implemented

1. PREFLIGHT — validate project, paths, platform binary, access, workspace changes and repo connection.
2. DISCOVER — retrieve repository revision, changed objects, and source/target versions.
3. PLAN — present differences; stop on unsafe conflicts or unapproved operations.
4. STAGE — reversible isolated intermediate state; never mutate originals before authorization.
5. APPLY — execute supported 1C/EDT operations with timeouts, cancellation and structured logs.
6. VERIFY — read actual target **repository** to confirm committed revision/object changes, not just the intermediary base.
7. FINALIZE — only after VERIFY, persist successful checkpoint and revision.

Interrupted/failed operations must never advance checkpoints. Do not silently retry operations with uncertain external side effects.

## Rules

- No production infobase or configuration repository writes.
- No reset, checkout, clean, force-push or overwriting uncommitted EDT/Git data.
- Credentials never enter logs or stored project settings.
- Local files, EDT model, Git branch, intermediate infobase and classical repository have distinct identities and revisions.
- Foreground SWT thread must not block on 1C operations or large model loads.
- Version incompatibility is an explicit error, not an automatic fallback to unsupported internal APIs.

See `docs/EDT_2026_2.md` and `docs/NEXT_STEP.md`.
