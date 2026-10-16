# Build and installation

## Prototype prerequisites

- JDK 21 for Maven/Tycho build (generated bundles target Java 17).
- Maven 3.9+.
- Internet access to public Eclipse p2 site (2024-06).

The CI build is **public Eclipse baseline only**, without proprietary 1C:EDT API bundles. This is intentional: the official EDT p2 target may require partner/ITS authentication. Build success here is **not** proof of compatibility with every EDT version.

```bash
mvn -B -ntp clean verify
```

Artifacts: `repository/org.xander.edt.storagebridge.p2/target/` (p2 ZIP). The CI workflow also publishes a build artifact on success.

## Development workspace

Import projects from `plugins/`, `features/`, `repository/` and `targets/` into an Eclipse PDE workspace and activate `targets/eclipse-baseline/eclipse-baseline.target`.

## Installing in 1C:EDT (after CI validation)

1. Unpack the produced p2 ZIP or choose **Archive…** in **Help → Install New Software…**.
2. Select **EDT Storage Bridge** and complete the normal p2 installation.
3. Restart EDT and open **EDT Storage Bridge → Inspect selected project**.
4. Select a workspace project; the dialog must show project name and location.

This is a read-only prototype. It does not communicate with 1C repositories. An official hosted update-site URL will be provided only after publishing and testing one.

## Later: EDT API target

Add an explicit, version-pinned EDT target and credentials in the build environment (not in source control), run verification for each supported EDT release, then enable EDT-specific adapters. No fake dependencies or local binary uploads to Git.
