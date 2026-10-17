# Round-trip 1C repository → XML → EDT → classic CF discrepancies (2026-10-17)

Status: **unresolved content discrepancies; a real fix cannot be proven from screenshots alone**.
This document records the actual user-observed evidence and safe reproduction.
Do not mark the applied repository revision verified while any relevant resource differs.

## Evidence from the user's full review

User's private `edt-storage-merge-review-7524608171906662893.txt`:
- 6,114 top-level owners examined; 6,109 byte-identical to the imported remote EDT stage
- five owner trees with differences, 16,614 pending identities cleared, 90 still pending
- `CommonPictures/ФорумСервиса`: imported stage contains `Picture.png`,
  but active EDT has no `Picture.png`
- `Configuration/Configuration`: `Configuration.distr` content differs
- `Subsystems/Кадры`: nested `Интеграция1СКС.mdo` differs
- `Subsystems/РегламентированнаяОтчетность`: nested `Прочие.mdo` differs
- `Subsystems/СтандартныеПодсистемы`: nested `КонвертацияОбъектно.mdo` differs

A separate classic Configurator comparison of a project-connected
infobase against a CF exported from the repository shows multiple metadata
differences. The screenshot is not a property-level diff, and cannot by
itself identify which stage introduced each changed property or ordering.

Native EDT compare logged four BSL modules falling back to non-structural
comparison, plus duplicate form-element names in two native form compares.

## Proven vs not proven

- In **full mode** the repository-connected intermediary is exported via
  `/DumpConfigToFiles <folder> -Format Hierarchical`, **without** `-listFile`.
- Full `IImportService.work(project, xml, stage, monitor)` is given the
  complete XML folder; the selective-only `EdtRootXmlGuard` does not run.
- The image `Picture.png` demonstrably reached the staged EDT representation.
  Its absence in the active project is a merge/application difference,
  **not proof of XML dump or import filtering**.
- `Configuration.distr` and nested subsystem mdo differences are not
  classified from the report alone. Byte differences may be real model
  differences, serialization, or merge decisions.
- 90 pending identities must not be cleared by clicking `Взять справа` alone.

## Changes made in the source

- The command `InspectProjectHandler` previously invoked a custom Guice
  injector through `BridgeExtensionFactory`, which produced
  `ArrayStoreException: EdtServiceModule`. It now uses `ServiceAccess`,
  like the functioning native import path; `plugin.xml` registers the
  handler directly.
- A read-only `IncomingPictureAudit` maps native picture-bearing paths
  from the original Designer XML to the temporary EDT stage and live EDT
  source, and reports image counts for the owners that fail exact validation.
  It NEVER copies files or changes the user's choices.
- Tests for a missing image after merge vs missing image after native import.

## Grounded isolation procedure (do not assume all discrepancies have one cause)

1. Verify the repository-connected intermediary is at the expected native
   revision, and independently export its **current** config to CF. Do not
   compare with a stale CF or a different revision.
2. Capture the full Designer XML output of that SAME intermediary, and verify
   that a suspect classic resource exists (e.g. common picture binary).
3. Import that unchanged XML into an isolated fresh EDT target with the same
   platform version. Compare XML→staged EDT resources to separate conversion
   loss from merge loss.
4. Let the user explicitly `Взять справа` in EDT. Compare staged EDT
   resources against live EDT immediately after the native merge.
5. Independently export the fresh imported EDT project and the merged project
   through supported EDT/1C tooling to CF, then compare **specific expanded
   properties** against the same classic CF baseline. That distinguishes
   conversion/serialization from merge choice, or an unrelated project-
   connected infobase update difference.
6. Verify module/form command bindings, nested subsystem topology, support
   state and common pictures. Do not manually copy files without validating
   EDT model consistency or override the repository checkpoint.

## Outstanding work

Exact semantic native EDT merge acceptance verification (model comparison
rather than byte comparison) and reproduction of the content differences
in a clean scratch project. Current changes are source-only pending manually
authorized CI and runtime testing in EDT 2026.2. Do not claim CF parity yet.
