# ADR 0003 — An intermediate infobase is NOT universally required

Date: 2026-10-16
Decision: accepted as the target architecture based on the project owner's request and inspection of real implementations.

## Evidence

- [PluginEDT](https://infostart.ru/1c/articles/2442956/) uses a **repository-connected infobase** to receive, capture and commit in the classical repository, and EDT project / Git branches for coordination.
- [gitsync](https://github.com/oscript-library/gitsync) and [v8storage](https://github.com/oscript-library/v8storage) support **direct** repository reading (including TCP/HTTP) and a recorded `VERSION` checkpoint, without claiming equivalent bidirectional safe writes from EDT.
- Documented `/ConfigurationRepositoryUpdateCfg` is a command of the 1C Designer operating in an infobase context; a connected disposable infobase may be required for supported writing and object locks.

## Decision

1. The **default** read-only transport should be direct where supported by a maintained/public API. This covers latest version, history and changed-object metadata.
2. An isolated 1C infobase is an **optional, justified adapter for write/receive** operations where supported platform commands actually require one; never introduce it merely out of habit.
3. Prefer correctness over optimizing away the necessary platform semantics for object capture/commit.
4. Never use a working/prod infobase without authorization. Never reverse-engineer/provide undocumented direct repository writes just to remove the staging database.
5. Both remotely observed and locally applied versions are distinct. Only confirmed responses may advance them; no arithmetic increments based on assumed versions.
6. Workspace prefs are keyed per project, not placed in Git; secret values are encrypted with Eclipse Secure Storage.

## Consequences

- No infobase path/credentials are required in the initial UI. The initial screen has repository address/user/password and version status only.
- Later transport implementations can be chosen independently and benchmarked, without changing the user-facing settings form.
- Direct read performance and object-delta semantics must be verified with a disposable test repository.
