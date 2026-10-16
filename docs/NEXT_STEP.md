# Immediate next milestone

**Target:** 1C:EDT 2026.2, PR #6, working branch `feature/edt-2026.2-integration`.

Completed: #38938763356 built the previous code (not the subsequent UI). Source now includes a project context settings dialog with encrypted repository password, last-observed / last-applied revisions, optional preconnected infobase and 1cv8.exe path.

## Platform read-only transport progress (added after last build)

- [x] New independent `org.xander.edt.storagebridge.platform` OSGi/Tycho module, containing only allowlisted 1C Designer history report generation.
- [x] File/server infobase location and requested version-range models.
- [x] Bounded `ProcessBuilder` with cancellation, timeout, output limits, temporary-file cleanup, private diagnostics and no credentials in argv.
- [x] Pure command-construction tests added **but not run**.
- [ ] Verify against real sample 1C TXT report and encoding; **no revision parser or version UI integration yet**.
- [ ] Explicit manually requested new CI compilation and runtime smoke tests.

See [read-only report design](READ_ONLY_REPORT.md).

## Next steps

1. User-authorized **manual** CI build of the new settings/dialog code, review Tycho errors before any retry.
2. Real installation into EDT 2026.2, check actual Navigator menu and filesystem/Secure Storage persistence.
3. Read-only transport spike: evaluate documented Designer repository report using /F or /S and a *separately approved disposable base*, plus direct-read alternative.
4. Identify whether the permanent connected base can be used safely: associated storage/user, captured objects, platform version and whether it is shared with another development process.
5. Introduce verified current storage revision, then operation plan for receiving changed objects.

**Never infer permission to execute commands on the existing connected base merely from permission to save its path.** No VPS/production repository use without express instruction, no automatic builds.
