# Static review of PR #6: EDT 2026.2 integration

Date: 2026-10-16
Review scope: **source and published API documentation only**. No compilation, unit tests, Tycho target resolution, installer run, or product smoke test was performed.

## Findings and repairs

| Severity | Finding | Change |
|---|---|---|
| High | EDT OSGi service could be initialized via Guice during command-handler construction on the workbench UI thread. | Inject `Provider<EdtProjectInspector>`; call `get()` only inside the background Eclipse Job. |
| Medium | User interface bundle directly bound `IConfigurationProvider`, violating the adapter boundary. | Moved vendor public-service Guice binding into `EdtServiceModule` in the `edt` bundle. The UI no longer imports `com._1c.g5.v8.dt.core.platform`. |
| Medium | Error handling used `BridgeActivator.getDefault().getLog()` while the activator could be null after plugin shutdown, producing a second exception. | Log via public `Platform.getLog(InspectProjectHandler.class)` with platform availability check and return `Status` containing the original exception. |
| Medium | SWT `Display` could become disposed while scheduling an asynchronous result callback during EDT shutdown. | Added defensive `Display` disposal checks with disposal-race handling; callbacks validate `Shell`. |
| Low | A project can be closed after command selection but before the Job actually reads it. | Added a second project accessibility check in `Job.run`. |
| Low | `plugin_ru.properties` had raw Cyrillic and could depend on the bundle-properties decoder. | Store Russian plugin.xml labels with standard `\\uXXXX` escapes. |

## Verified publicly documented contracts

- `IConfigurationProvider.getConfiguration(IProject)` returns `Configuration` or `null`:
  https://edt.1c.ru/dev/edt/2025.2/apidocs/com/_1c/g5/v8/dt/core/platform/IConfigurationProvider.html
- EDT public OSGi services can be bound with `AbstractServiceAwareModule.doConfigure()` and `toService()`:
  https://edt.1c.ru/dev/wiring/2.5/apidocs/com/_1c/g5/wiring/AbstractServiceAwareModule.html
- `AbstractGuiceAwareExecutableExtensionFactory` is designed for Eclipse extension instantiation:
  https://edt.1c.ru/dev/wiring/2.5/apidocs/com/_1c/g5/wiring/AbstractGuiceAwareExecutableExtensionFactory.html
- Eclipse logging via `Platform.getLog(Class<?>)` is a public API:
  https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/core/runtime/Platform.html
- Eclipse plug-in language properties may use Unicode escapes:
  https://www.eclipse.org/articles/Article-Internationalization/how2I18n.html

**Compatibility note:** online service Javadocs above use older published API series. The exact EDT **2026.2** bundle exports and method signatures remain a manual-build verification gate, not an assumed result.

## Still unverified until an approved build

1. 1C:EDT 2026.2 p2 repository authentication and complete Tycho dependency closure.
2. Java 25 bytecode compatibility against the user's concrete EDT 2026.2 build.
3. Guice cross-bundle instantiation of `EdtProjectInspector` with the 2026.2 OSGi public service.
4. Execution of `IConfigurationProvider.getConfiguration(project)` from a Job thread and behavior under cancellation.
5. Plugin command visibility in EDT navigator, Russian label rendering, and installation from p2 ZIP.
6. Background Job cancellation cannot forcibly interrupt a blocking EDT service call; no timeout is claimed at this stage.

## Next authorized acceptance test

On a single explicit user instruction to build, run the **manual** `Build and test` workflow; inspect target resolution, Java compilation, OSGi test results, and p2 archive. Following that, smoke-test in a disposable EDT 2026.2 installation, noting exact patch number and logs. Do not merge PR #6 before its acceptance gates pass.
