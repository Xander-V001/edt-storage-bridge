# Next step — verify receiving changes, not merely report history

Working branch: feature/edt-2026.2-integration, PR #6.
Code added since the last successful manual build #38944234655:
- Explicit "Получить изменения" button in the project storage dialog.
- Platform Designer receive command with confirmation for new/deleted objects but no -revised.
- Safe bounded subprocess, no passwords in command line.
- Public EDT sync adapter to apply changes to the existing EDT project; refusal on model divergence.
- Git branch/HEAD/dirty preflight; no checkpoint changes on partial or unknown results.
- New JUnit tests of command construction (not yet executed).

See [receive proof and safety contract](RECEIVE_CHANGES.md).

**Now:** next manual build ONLY on explicit request; then install/smoke test in a disposable EDT 2026.2 project with disposable connected infobase. Do not use the user's permanently connected base yet. Prove exact IB match/lock state and per-branch revision checkpoint before production use. No VPS.
