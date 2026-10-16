# Current milestone — project storage form and direct-read research

Target: **1C:EDT 2026.2**.

## Already compiled
- GitHub Actions #38938763356 — 8 modules succeeded, 3 core tests passed, p2 ZIP produced.
- Builds require manual `workflow_dispatch`; automatic triggers are forbidden.
- This build **predates** the storage settings/context-menu source.

## Written since last build (NOT compiled)
- Right-click project → `Работа с хранилищем` → dialog.
- Address, username and encrypted Eclipse Secure Storage password, scoped to project.
- Independent persisted last-observed-remote / last-verified-applied checkpoints.
- No remote test or automatic update is claimed; no 1C repository touched.

## Next tasks
1. Manual build **only when the user explicitly requests one**, fix compile/p2 errors.
2. Smoke-test project context menu in the real EDT 2026.2 Navigator.
3. Verify UI settings persist between EDT restarts and passwords never appear in project files.
4. Research supported direct read-only repository API and verify latest version/history against disposable repository.
5. Update remote observed revision only after a real read-only response; update applied revision only after a validated receive into EDT.
6. Evaluate 1C Designer-connected staging base only if direct supported write capability is absent or less safe.

No production/VPS access or user working repository writes.
