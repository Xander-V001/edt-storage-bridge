# Roadmap (target 1C:EDT 2026.2)

## Phase 0 — baseline
- [x] Repo, modular bundles, manually triggered GitHub Actions and artifact cleanup
- [x] Successful **manual** 1C:EDT 2026.2 Tycho build: GitHub Actions #38938763356, all modules; 3 JUnit tests passed
- [x] p2 ZIP generated; not installed/smoke-tested in a live EDT session

## Phase 1 — plugin UI and project settings
- [x] Context command `Работа с хранилищем`, project-specific connection/settings form in source
- [x] Masked password in Eclipse Secure Storage, address/login in workspace-only instance preferences
- [x] Independent remote-observed and EDT-applied revision fields, displayed as unknown until verified
- [ ] **Manual build and runtime smoke test of new UI code** when user explicitly requests
- [ ] Confirm command is visible in actual EDT 2026.2 Navigator
- [ ] Test password re-open and storage changes without leaking secrets

## Phase 2 — read-only classic configuration repository
- [ ] Confirm supported read-only transport (direct where feasible); interrogate test repository
- [ ] Show current remote version, history and changes with authenticated request
- [ ] Persist observed remote version ONLY after confirmed response; no guessed increments
- [ ] Compare speed/direct-access availability against staging-base alternatives

## Phase 3 — receive into EDT
- [ ] Detect local changes/conflicts and reconcile safely
- [ ] Implement import/reconciliation through public EDT API
- [ ] Make isolated staging database **optional**, only if required for verified platform commands
- [ ] Persist last-applied checkpoint **only after successful verified import**

## Phase 4 — send to 1C configuration repository
- [ ] Validate object locks and concurrent version changes
- [ ] Staging/export, platform-backed upload and read-after-write verification
- [ ] Recovery journal with no false success

## Phase 5 — optimize and distribute
- [ ] Benchmark large real configurations and object-level changes
- [ ] Add safe incremental-only transfer when supported
- [ ] Versioned installer and reliable upgrade workflow
