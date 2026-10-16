# Roadmap

## Phase 0 — bootstrapping
- [x] GitHub repository and baseline documentation
- [x] Modular Eclipse bundles with a read-only project inspection command (source)
- [x] Tycho / feature / p2 repository / CI definitions (source)
- [ ] CI confirmed green and p2 ZIP inspected
- [ ] Manual smoke test with 1C:EDT 2026.1.x

## Phase 1 — verification
- [ ] Pin EDT API target against installed EDT (and p2 credentials if required)
- [ ] Identify canonical import/export and staging APIs
- [ ] Build isolated test base and test configuration repository
- [ ] Read-only connection test and repository revision detection
- [ ] Failure-mode matrix and error classification

## Phase 2 — safe receive
- [ ] Object-level change plan and conflict detection
- [ ] Reversible staging + receive + verification + checkpoint

## Phase 3 — safe commit
- [ ] Validate selected objects, locks and current revision
- [ ] Apply, repository commit, read-after-write verification
- [ ] Recovery / reconciliation for uncertain outcomes

## Phase 4 — quality and distribution
- [ ] Performance benchmarks with representative configurations
- [ ] Parallelization only when 1C operations are demonstrably safe
- [ ] Installer/update site, release signing policy, backwards compatibility
