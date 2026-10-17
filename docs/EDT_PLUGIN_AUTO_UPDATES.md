# Automatic EDT plugin updates via GitHub Pages (p2)

## One-time GitHub setup (completed by repo owner, 2026-10-17)

- `Xander-V001/edt-storage-bridge` is **public**.
- GitHub Pages is enabled. In **Settings → Pages → Build and deployment**, the source should be **GitHub Actions** (not "Deploy from a branch").
- GitHub Pages URL for EDT p2: **https://xander-v001.github.io/edt-storage-bridge/**.
- This URL is not an installable p2 repository until the **first successful Pages deployment**.

## Publishing a version

The same `.github/workflows/ci.yml` handles the manually authorized build
and its subsequent publication. It still has **only** `workflow_dispatch`,
with no push, PR, scheduled, or merge triggers.

1. Repo owner explicitly authorizes **a particular build**.
2. Trigger `Actions → Build and test (manual) → Run workflow` and choose
   `feature/edt-2026.2-integration` while development remains on that branch
   (the default `main` branch can contain older source).
3. Tycho compiles/tests, writes `target/repository` p2 metadata and
   feature/plugin jars, and still uploads the short-lived offline p2 ZIP.
4. The build job validates the native p2 directory structure and ensures no
   `com._1c.*` vendor jars are published.
5. A separate `publish` job (no second build) deploys those generated site
   files with official `actions/upload-pages-artifact@v4` and
   `actions/deploy-pages@v4` actions to the `github-pages` environment.
   **Only a successful manually launched build** reaches this job.
6. Tycho uses an UTC timestamp plus GitHub run number via
   `-DforceContextQualifier` to make each new OSGi/p2 version strictly
   newer than an older build created in chronological order.
7. The site contains **only the latest approved build**. p2 upgrades directly
   from installed older versions to latest; historical version hosting is not
   promised. The static GitHub Pages URL is stable.

The publishing workflow uses the GitHub-provided token with
`pages: write` and `id-token: write`, not a new user PAT or SSH credential.
The vendor target p2 credentials remain only in existing Actions secrets.

## First-time setup inside 1C:EDT 2026.2

1. Open **Help → Install New Software…**, or the equivalent 1C:EDT command
   for Eclipse software installation.
2. Enter `https://xander-v001.github.io/edt-storage-bridge/` as the repository
   location and select **EDT Storage Bridge**.
3. Finish the native installer and restart EDT when prompted. This installs
   from the same update source on future updates.
4. Under **Window → Preferences → Install/Update → Automatic Updates**
   (if exposed by the EDT distribution), enable periodic update checks and
   **notification/confirmation**, not unattended installation.
5. For plugins already installed from a ZIP, register the same URL under
   **Preferences → Install/Update → Available Software Sites**, then invoke
   **Help → Check for Updates**.

Exact menu names may differ due to EDT-specific Eclipse packaging. Installing
an update still requires the user's explicit confirmation and usually a restart.
No code in this project silently replaces live EDT plugin jars.

## Debugging

- If the URL returns 404: verify **Actions → Build and test (manual)**
  published successfully and **Settings → Pages** says source GitHub Actions.
  `has_pages=true` on a GitHub repository does NOT mean content was deployed.
- If the build is successful but Pages fails: check `publish` logs,
  `github-pages` environment branch restrictions and Pages source mode.
  Do not rerun Maven or launch additional builds without the owner's approval.
- If EDT says no updates: check that the p2 site is registered, p2
  `content.jar` and `artifacts.jar` are accessible and the new feature
  OSGi qualifier is lexicographically later than the installed version.
- Successful CI/publishing only proves packaging/deployment; it **does not**
  prove the classic 1C storage selective receive/merge mechanism works safely.
  The mandatory Document1/Document2 acceptance test is separate.
