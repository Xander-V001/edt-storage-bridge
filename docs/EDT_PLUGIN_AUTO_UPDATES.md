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


## 2026-10-17: installation failure in EDT from build #31

The user's actual Eclipse p2 log shows `Collect` failure: `No repository
found containing` all four `org.xander.edt.storagebridge.*` bundles and
the feature at version `0.1.0.202610171357`. The unrelated Eclipse
`Disconnected before saving` generic-text-editor stack trace is not
evidence that the bridge itself failed to compile or install.

**Archive verified, do not falsely label it missing or corrupt:** downloaded
the actual build #31 GitHub Actions artifact (`run 39038658393`), unwrapped
its one nested p2 ZIP, and checked all 5 JAR entries and `artifacts.jar`.
Their sizes, SHA-256 hashes, versions and ZIP CRC match. The failure concerns
p2's ability to load/download artifacts from that installed `jar:file:`
archive, **not missing physical JARs**. The exact Windows-specific mechanism
is still unproven.

Source-only corrections after run #31 (no unauthorized CI):
- Normalize the generated p2 index to conventional JAR-backed
  `content.xml` / `artifacts.xml` *factories*, which prefer `content.jar`
  and `artifacts.jar`. This avoids relying on `.xml.xz` factories when
  loading the offline ZIP; Eclipse p2 documents this factory selection.
- Repackage the offline archive using exactly the validated site contents.
  Verify all bundle/feature payload lengths and SHA-256 against the
  `artifacts.jar` descriptors; reject mismatched versions, missing IUs,
  missing JARs, duplicate paths or bad CRC.
- Require an isolated **native Eclipse p2 Director** installation of the
  self-contained core bundle from the `jar:file:...zip!/` path before
  publishing the site and uploading ZIP. Core verifies actual p2 repository
  loading/artifact retrieval; the other four JARs are checked against
  artifact metadata. This does NOT prove native EDT feature compatibility,
  nor the classic 1C receive/merge workflow.
- The normal source workflow remains manual-only, `workflow_dispatch`.
  Do not launch a build for this correction without a new explicit request.

Until a successful approved build and p2 install smoke test, do not suggest
the old #31 ZIP is a functional installable release. Once Pages is published,
prefer the HTTPS p2 URL; unzipping an offline p2 ZIP and adding its extracted
directory can be used for diagnosis if required.


## 2026-10-17: CI #33 — Director succeeded; faulty filesystem assertion failed

Run [#33](https://github.com/Xander-V001/edt-storage-bridge/actions/runs/39040968164)
compiled and tested all modules, normalized the offline ZIP, and reported:
`Installing org.xander.edt.storagebridge.core 0.1.0.20261017143006-33`,
`Operation completed in 220 ms`, `BUILD SUCCESS` from the native p2
Director. The NEXT shell command wrongly expected a JAR inside the chosen
installation folder, but the Director can store bundles in a shared p2 pool;
this assertion exited 1 and blocked the upload/deploy steps. It did NOT mean
p2 Director failed to retrieve or install that test bundle.

The manual workflow now uses the Director process success as the authoritative
result instead of an unsupported assumption about physical artifact placement.
The separate preflight still validates the complete ZIP, all four bridge jars,
feature jar, checksums and p2 installable units before Director is called.
Official GitHub Pages action inputs and permission settings were reviewed.
Changes are source only until an explicitly approved new build completes.
