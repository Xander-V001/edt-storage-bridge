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

## Receive stage (UNBUILT; NOT FUNCTIONAL)
- [x] Removed invalid EDT-infobase association and unsafe receive entry point.
- [x] Added read-only /DumpConfigToFiles -listFile selective export from the existing storage-bound intermediary with private temp cleanup.
- [ ] Determine changed objects and select the minimum required delta.
- [ ] Transform the delta into EDT format, show official compare, safely merge selected objects.
- [ ] Build and disposable test only on explicit user request.

## Previous discarded prototype (reference only; no longer wired)
- [x] Source of guarded Designer /ConfigurationRepositoryUpdateCfg -force runner (no -revised), no password argv.
- [x] **Removed:** attempted EDT infobase synchronization based on false linked-base assumption; source deleted.
- [x] **Disabled:** receiving UI until selective project merge is verified. No EDT base picker.
- [x] Clean task-branch Git preflight; blocked unknown EDT synchronization baseline, canceled operations never advance checkpoint.
- [ ] **Manual CI compilation, runtime smoke test in disposable EDT 2026.2**, no authorized live user base access.
- [x] **Removed** irrelevant EDT infobase identity matcher; intermediary base has no EDT association.
- [ ] Actual 2026.2 compile/runtime of native callback, full compare/merge behavior and object skipping.
- [ ] Classic storage repository identity check, session/lock checks, authentic version verification, per-object checkpoints.

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
- [x] Replace automatic conflict resolver with public EDT native interactive compare callback (source only; awaits real 2026.2 acceptance).
- [ ] **Blocking before working-base use:** prove native compare opens before applying to project and supports selective object-level accept/skip, without hidden Git branches.
- [ ] Correctly record object-level deferred changes; whole-project version must not advance if some objects are skipped.
- [ ] Object-level plan, conflict checks, dependency-aware receive.
- [ ] Approved use of existing connected base where platform operations require it.
- [ ] Public EDT import, outcome verification, applied revision checkpoint and recovery journal.

## Stage 4 — capture/commit to repository
- [ ] Check other captures and concurrent versions, verify platform/infobase state.
- [ ] Capture, apply and commit using proven supported operations, no destructive force switches by default.
- [ ] Verify repository new version and safe rollback/recovery paths.

## Stage 4a — required local Git task workflow
- [ ] Identify current local Git repository and active task branch (one task = one feature branch).
- [ ] Keep repository and Git baseline checkpoint **per branch**, not merely per EDT project directory.
- [ ] Collect storage revisions in disposable staging **without creating any Git branches or worktrees**.
- [ ] Present candidate **unpublished local Git commits**, object-level changes and a safe contiguous range selection.
- [ ] Build reproducible mapping: selected Git commits + object changes → one or more verified classical storage revisions.
- [ ] Receive: always show compare/merge window; apply selected objects to current branch without touching other objects, preserving skipped-object status.
- [ ] Push: refuse dirty tree, ambiguous commit ancestry, unsupported object diffs, unknown storage baseline or conflicting repository changes.
- [ ] Never equate Git commit count with storage revision increments.

## Stage 5 — performance/distribution
- [ ] Benchmark large configurations, optimize delta transfer and caching.
- [ ] Repeatable smoke tests, signed p2/release workflow.
