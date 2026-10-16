# Roadmap — 1C:EDT 2026.2

## Stage 0 — baseline (done)
- [x] Module skeleton (core/edt/ui), Maven Tycho 5.0.2, Java 25, official EDT target.
- [x] CI manual-only, artifact cleanup; #38938763356 success, 3 JUnit tests.
- [ ] Live install and smoke test.

## Stage 1 — project context UI (source changes only)
- [x] Right-click project → Работа с хранилищем.
- [x] Repository address/user + encrypted Eclipse Secure Storage password.
- [x] Optional connected infobase mode File (/F) / Server (/S), location, installed platform 1cv8.exe path with browse buttons.
- [x] Two separate persisted revisions; never invent/advance on save.
- [ ] One user-requested build and real EDT 2026.2 installation.
- [ ] Verify preferences persistence and secure-password behavior across restarts.

## BLOCKER / next technical priority — real receive into existing EDT project
- [ ] Verify exact 2026.2 API or CLI for **updating an existing project from an infobase**, with merge/conflict preservation.
- [ ] Complete controlled end-to-end test from disposable 1C repository → connected base → existing EDT project.
- [ ] Test divergent local edits, new and deleted objects; never mark applied revision on partial success.
- [ ] Implement minimal user-invoked receive action after proving mechanism.
- See [critical-path proof](END_TO_END_RECEIVE_PROOF.md). Additional history parser/UI work is secondary until this is proven.

## Stage 2 — safe connection and read-only inspection
- [x] Read-only 1C Designer history-report module and bounded process runner (source only; not built)
- [x] TXT UTF-8/UTF-16 BOM decoder and pure tests (added, not run)
- [ ] Validate configured connected-base identity and compatible repository user **without rebinding**.
- [ ] Test version/history access using documented Designer report and direct transport.
- [ ] Select transport with actual evidence: correct results, side effects, performance.
- [ ] Show live remote revision and list of changed repository objects.
- [ ] Update last-observed remote revision only after verification.

## Stage 3 — receive from repository
- [ ] Object-level plan, conflict checks, dependency-aware receive.
- [ ] Approved use of existing connected base where platform operations require it.
- [ ] Public EDT import, outcome verification, applied revision checkpoint and recovery journal.

## Stage 4 — capture/commit to repository
- [ ] Check other captures and concurrent versions, verify platform/infobase state.
- [ ] Capture, apply and commit using proven supported operations, no destructive force switches by default.
- [ ] Verify repository new version and safe rollback/recovery paths.

## Stage 5 — performance/distribution
- [ ] Benchmark large configurations, optimize delta transfer and caching.
- [ ] Repeatable smoke tests, signed p2/release workflow.
