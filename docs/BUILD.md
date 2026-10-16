# Build / IDE setup (target: 1C:EDT 2026.2)

**Never trigger a build automatically.** A build must be explicitly requested by the user and manually launched via the GitHub Actions `workflow_dispatch` workflow.

## Proposed toolchain — NOT YET VERIFIED

- **JDK 25 with JavaFX** (official EDT plugin sample uses Zulu 25 JDK FX).
- **Eclipse 2025-12** and **1C:EDT 2026.2** p2 artifacts.
- **Maven 3.9.9** and **Tycho 5.0.2**.
- The target source is `targets/edt-2026.2/edt-2026.2.target`.

1C p2 repositories may require registered user portal credentials. The manual workflow is prepared for optional GitHub Actions secrets `P2_USER` and `P2_TOKEN`, passed by environment variable names to a Maven `dt_repository` server setting as in the official 1C sample. **No secrets have been configured or requested**; they are only needed if the vendor requires authentication. Never commit or paste credentials. Verify target access before the first approved CI build.

## Development in Eclipse PDE

Import modules in `plugins/`, `targets/`, `tests/`, `features/`, `repository/` as existing projects, activate `targets/edt-2026.2/edt-2026.2.target`, and resolve the target. Use the product-compatible Java 25 installation. Do not use the obsolete Eclipse 2024-06 target — it is not a 2026.2 runtime.

## Authorized build command (reference only; DO NOT execute without explicit request)

```bash
mvn -B -ntp clean verify
```

The CI workflow uses the same Maven command on manual dispatch. On success it publishes a short-retention p2 ZIP. It removes older artifacts using the manual artifact policy.

## Runtime check (after build and user approval)

Install p2 ZIP into a **disposable test installation of EDT 2026.2**. Run `EDT Storage Bridge → Inspect selected project` for an EDT configuration project and verify only model availability is read.

**Important:** the old p2 ZIP created before the 2026.2 migration is not an EDT 2026.2 verified release. It must not be represented as such.

No integration with the configuration repository is yet implemented.
