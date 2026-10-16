# Roadmap (target 1C:EDT 2026.2)

## Phase 0 — baseline (historical Eclipse-only proof)
- [x] Repository and modular plugin foundation
- [x] Maven Tycho/p2 ZIP and 3 JUnit tests passed against **Eclipse 2024-06**, not EDT
- [x] Manual-only CI and artifact retention policy

## Phase 1 — real EDT 2026.2 integration
- [x] Choose EDT 2026.2, Eclipse 2025-12 and Java 25
- [x] Write the new 2026.2 target, public EDT API adapter and Guice integration
- [ ] **Manual build requested by user** and target resolves
- [ ] New p2 archive installed and read-only probe smoke-tested in EDT 2026.2
- [ ] Confirm specific installed EDT patch/build
- [ ] Read-only connection to a disposable 1C configuration repository
- [ ] Capture real revision/status response and error cases

## Phase 2 — safe receive
- [ ] Object-level plan and conflict detection
- [ ] Reversible staging, receive, verification and checkpoint

## Phase 3 — safe commit
- [ ] Validate objects, locks and current repository revision
- [ ] Commit to test storage with read-after-write verification
- [ ] Recovery for incomplete/uncertain operations

## Phase 4 — quality and distribution
- [ ] Representative performance benchmarks
- [ ] Parallelism only where actually safe
- [ ] Signed/versioned p2 update site and backwards compatibility policy
