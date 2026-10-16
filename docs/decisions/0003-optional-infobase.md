# ADR 0003 — Connected infobase as optional supported adapter

Date: 2026-10-16; revised after user confirmed an existing permanently connected base is available.

## Decision

Use a **connected infobase**, when already available, as the preferred candidate for platform-supported repository operations such as capture, receive, commit and building history reports. However, **do not make it mandatory for every operation**. Investigate direct read-only repository access and compare with Designer reporting.

This is a hybrid by-operation architecture, not an always-required extra database. The user can supply:
- Repository address/user/password (always);
- Optional `/F` file directory or `/S` server connection, selected explicitly;
- Optional installed platform `1cv8.exe` path.

The existing linked infobase is *not* created, cloned, automatically rebound or updated by entering settings. Before using it, validate its linkage, user identity, platform version and current captures. A connected infobase may contain developer work and must never be treated as a disposable scratch area.

## Reasoning

- The official 1C methodology presents bound infobases as the normal work mode, preserving locks, dependency rules and user bindings: https://its.1c.ru/db/content/metod8dev/src/developers/platform/metod/devtools/i8102287.htm .
- Supported Designer `/ConfigurationRepositoryReport`, `/ConfigurationRepositoryUpdateCfg` and related commands can use a selected infobase; the latter modifies configuration and is **not** a read-only probe.
- Direct-read tooling such as gitsync/v8storage offers potential simpler version/history querying. This does **not** establish safe supported direct write semantics.
- The user's previous automation used a permanent connected base successfully, providing a practical candidate for integration and avoiding premature reimplementation of storage internals.

## Consequences

- The UI includes optional connection base and platform fields immediately; no base commands execute yet.
- Selecting connected-base mode will not be conflated with storage address, repository user or EDT project checkpoint.
- Changes to repository identity invalidate cached revision checkpoints; changes to base path alone do not assert any completed sync.
- Work with a real connected base requires an explicit user-approved test and safety checks. No silent `/ConfigurationRepositoryBindCfg`, `-revised`, `-force`, `/UpdateDBCfg` or repository commit.
- All builds are still manual-only; future implementation and benchmarks use isolated fixtures when feasible.
