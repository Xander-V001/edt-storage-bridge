# Next concrete step

Current implementation on branch `feature/edt-2026.2-integration` / PR #6:

- Real native EDT interactive comparison callback wired to the receive path (no custom resolver).
- Selected EDT IB identity checked against Designer /F or /S address.
- Current task Git branch/HEAD/working tree preserved.
- Explicit receive confirmation; no automatic repository version checkpoint.
- No technical Git branches.

**Current gate:** source has NOT been built or tested with EDT 2026.2. Only a user-requested manual build may be started. Then use disposable bound IB/project to validate compare/merge and skipped object workflow. Real user working infobase is not an authorized test target.

Read [receive implementation and safety](RECEIVE_CHANGES.md).
