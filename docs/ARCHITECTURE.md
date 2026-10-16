# Architecture

## Boundaries

- **core**: immutable values and deterministic validation logic. No Eclipse UI, no 1C calls.
- **ui**: Eclipse workbench commands, project discovery and user interaction.
- **repository adapter (planned)**: 1C platform command execution, safe subprocess handling, object lists and version checks.
- **EDT adapter (planned)**: verified 1C:EDT APIs for synchronization; never bind to internal/non-exported packages without explicit analysis.
- **Git adapter (planned)**: optional; storage synchronization must not require Git metadata.

The initial artifact implements only a read-only diagnostic command.

## Synchronization lifecycle (design contract, not implemented)

1. PREFLIGHT — verify workspace, paths, 1C tools, permissions and working tree.
2. DISCOVER — read exact repository revision and changed-object list.
3. PLAN — present differences and require explicit confirmation for destructive operations.
4. STAGE — create reversible isolated staging state; preserve original data.
5. APPLY — execute supported 1C/EDT operations with bounded timeouts and cancellation handling.
6. VERIFY — query the actual target repository to confirm revision and object changes.
7. FINALIZE — mark a successful checkpoint only after VERIFY has succeeded.

On any error: persist diagnostic phase and error code, do **not** advance checkpoint, do **not** claim synchronization completed. Never silently retry an operation with uncertain side effects.

## Mandatory invariants

- No writes before preflight success and user confirmation.
- No reset, checkout, clean, force-push, or overwriting uncommitted EDT files.
- Local source changes and repository state are never conflated.
- No credentials in logs, artifacts or exceptions.
- Staging/database operations must be separately traceable.
- Final status reflects confirmed state of 1C repository, not only an intermediate infobase.
- First integration tests must use disposable data exclusively.

## Key unknowns

- Exact supported EDT import/export APIs for the installed product version.
- Locking and status semantics of classical 1C configuration repository commands.
- Credentials and official p2 target distribution restrictions.
- Matching unchanged and renamed metadata objects between EDT and 1C repository.

No assumptions above are treated as implemented behavior.
