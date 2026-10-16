# Next development step: read-only EDT 2026.2 validation

**Decision (2026-10-16):** the user works in **1C:EDT 2026.2**. Versions 2026.1.x are no longer the target.

## Implemented without building

- Target platform declared for 1C:EDT 2026.2 / Eclipse 2025-12 / Java 25.
- Dedicated EDT-specific adapter using the documented `IConfigurationProvider.getConfiguration(IProject)`.
- Guice/OSGi extension factory, read-only asynchronous project diagnosis.
- Manual-only CI remains intact and now declares Java 25.

## Next validation gate (only when explicitly requested)

1. Resolve vendor 2026.2 p2 target; compare resolved bundle versions with installed EDT.
2. Compile and run existing tests by a single manual workflow dispatch. Inspect exact failure logs, if any, before altering dependencies.
3. Install the new p2 ZIP into a disposable EDT 2026.2 setup.
4. Confirm the menu command and project model diagnostic; check EDT Error Log, cancellation and UI responsiveness.
5. Record the exact tested EDT patch/build and successful output.

Until then all 2026.2 Java changes are **unverified**, not release-ready.

## Then: classic 1C repository read-only discovery (Issue #3)

- Determine supported platform command line with versioned 1C documentation. `/ConfigurationRepositoryReport` appears to produce a text/MXL history report **via a designer/infobase context**; it must not be mistaken for a standalone stateless read API.
- Determine how to interrogate a disposable repository and intermediary infobase without modifying configuration/repository state.
- Separate transport credentials, repository version, EDT workspace project, intermediary infobase revision and Git commit.
- Design a bounded/cancelable subprocess runner, proper stdout/stderr consumption, file permissions and result parser from actual sample output. No speculative parser.
- Implement status/revision snapshot only after behavior is verified against a disposable fixture.

No automatic builds, production 1C actions or VPS access.
