# Immediate next milestone

**Target:** 1C:EDT 2026.2, PR #6, working branch `feature/edt-2026.2-integration`.

Completed: #38938763356 built the previous code (not the subsequent UI). Source now includes a project context settings dialog with encrypted repository password, last-observed / last-applied revisions, optional preconnected infobase and 1cv8.exe path.

## Next steps

1. User-authorized **manual** CI build of the new settings/dialog code, review Tycho errors before any retry.
2. Real installation into EDT 2026.2, check actual Navigator menu and filesystem/Secure Storage persistence.
3. Read-only transport spike: evaluate documented Designer repository report using /F or /S and a *separately approved disposable base*, plus direct-read alternative.
4. Identify whether the permanent connected base can be used safely: associated storage/user, captured objects, platform version and whether it is shared with another development process.
5. Introduce verified current storage revision, then operation plan for receiving changed objects.

**Never infer permission to execute commands on the existing connected base merely from permission to save its path.** No VPS/production repository use without express instruction, no automatic builds.
