# Native 1C selective dump: verified failure and safe normalization

## 2026-10-17 user repro — installed EDT 2026.2

The supplied `designer.txt` from a real failing `/DumpConfigToFiles
-listFile` invocation reported:

> Невозможно сохранить объект метаданых
> Catalog.Б24_УП_НастройкиПодключения.Command.НастройкиПодключения
> в отдельный XML-файл. Вы можете сохранить его как часть
> родительского объекта.

The supplied `objects.txt` contains 16,945 entries, **268** of which
have a nested `*.Command.<name>` segment, and all 268 owners were
already present. Other independently developed objects must not be
indiscriminately removed: forms, templates, modules, rights etc.

## Fix (source only until user-authorized build)

- `SelectiveDumpPlanner.plan()` normalizes nested commands
  (`Catalog.X.Command.Y`, `Document.X.Command.Y`, etc.) to the owner
  (`Catalog.X`, `Document.X`). It deduplicates targets and continues
  exporting separately developed forms/modules when requested.
- `ConfigurationChangesParser.ChangeSet.objectsToExport()` does not
  emit nested Command entries as separate export targets.
- `ReceiveChangesCoordinator` retains **original pending identities**,
  not only normalized native export names. If `Catalog.X.Command.Y`
  was changed, it remains pending until the staged **owner** `Catalog.X`
  is confirmed exactly equal in EDT. Deferred root entries are not
  silently accepted.
- Tests cover the actual repro name, a command-only delta, and
  preservation of independently developed child sources.
- Static transformation of the user's original list results in
  16,677 distinct entries, with no `*.Command.<name>` as a standalone
  native target. This is **not** proof of a successful native Designer
  run: the current dump may still reveal additional unsupported names.

## Sequencing and critical remaining limitations

The normal `ReceiveChangesCoordinator` sequence already executes
`/ConfigurationRepositoryUpdateCfg -force` against the EXISTING
repository-connected intermediary **before** selective `/DumpConfigToFiles`.
There is an explicitly confirmed pending-only retry that skips the
update if a read-only version index matches the prior snapshot.
`RepositoryUpdateRunner` currently returns success on native exit=0,
and post-update snapshot success is also checked as of commit af88576.
**Neither proves the exact source repository revision or that the
infobase equals the current HEAD of storage.** A separately verifiable
storage revision proof is still required.

The supplied native log also said `Соединение с хранилищем
конфигурации не установлено`. That text was recorded by the separate
selective-export Designer process, and does NOT independently establish
whether a preceding storage-update Designer process succeeded.
The update operation's native output is currently deleted, so claiming
the exact applied revision would be unjustified.

No new CI build is started automatically. Do not use the user's VPS or
working infobase for experiments. Initial EDT/store reconciliation
intentionally differs from subsequent small incremental updates;
never replace it with a blind overwrite of the EDT project.


## 2026-10-17 — Next real EDT 2026.2.1.3 failure: root XML conversion

The user provided EDT event log after the earlier embedded Command fix.
The native selective Designer dump reached the EDT conversion step, proving
that the previous \`-listFile\` exception was no longer the immediate blocker
for this attempt. Eclipse reported two failures in the **native EDT importer**:

- \`Ext\HomePageWorkArea.xml\` — \`ExecutableHierarchyImporterNode.createAndSaveResource\` throws
  \`NullPointerException\` because its computed relative path is null.
- \`Ext\ClientApplicationInterface.xml\` — same class and failure.

Then \`EdtSelectiveReviewService.prepare\` rejected the failed
\`IImportService.work(project, externalXmlRoot, externalEdtRoot, monitor)\`.

### Conservative interim correction, pending native validation

The current importer cannot reliably convert global configuration \`Ext\`
resources into **detached** EDT files. This is an incompatible XML import
shape, not evidence of a Git or Designer selective export failure. Avoid
attempting to fix it by importing XML into the active EDT project.

- The receiving planner now defers all \`Configuration.*\` (and the bare
  \`Configuration\`) names, retaining original pending identities. This
  intentionally postpones even supported root module changes rather than
  claiming they were merged. They require a separately proven converter.
- When importing unrelated selected objects, \`EdtRootXmlGuard\` parks ONLY
  top-level \`Ext\` from the plugin-owned temporary Designer dump, invokes
  \`IImportService.work\`, and restores that folder in a \`try-with-resources\`
  block. It must never hide root files when any explicit Configuration
  selection is present. Nested \`Catalogs/Name/Ext\` etc. stay untouched.
- The comparison scope is still restricted to explicitly requested objects.
  \`ReceivePendingStore\` continues tracking all deferred records; they are
  not marked applied. The existing 1C connected intermediary and active EDT
  Git worktree remain untouched.
- Unit tests cover root deferral and temporary-folder restoration under
  success/no Ext/root explicitly selected. No native EDT 2026.2 test or
  user build has been run for this change. Other native XML conversion failures
  are possible, and any unsupported configuration-root changes remain
  outstanding rather than silently discarded.

The same log includes unrelated p2 authentication errors for
\`services.1c.dev\`, a Windows Defender service-query timeout and warnings
from other installed plugins. Those are not the immediate cause of the
\`EdtSelectiveReviewService.prepare\` error shown at 18:15:55.

## 2026-10-17 18:30 — third real failure: unsupported EDT source type mapping

The user-provided EDT 2026.2.1.3 event log now shows
`EdtSelectiveReviewService.edtOwner:233`, called by `exactScope`
AFTER `IImportService.work` completed without the previous `Ext` importer
NPE. At this line the source throws for an unmapped metadata type.

The previously provided actual `objects(1).txt` (16,945 names)
contains 40 distinct metadata type prefixes, including the single
`Configuration` root. Of those, the existing EDT mapping omitted
**exactly three**: `SettingsStorage` (28 entries),
`CommandGroup` (11), `IntegrationService` (2).

Correction: add the native source group folders
`SettingsStorages`, `CommandGroups`, `IntegrationServices`
and Russian aliases. Test these mappings, cover all 39 ordinary
metadata categories observed in the supplied object list, and aggregate
unmapped type names before invoking the native Designer export. The
UI reports only safe type identifiers, never complete storage object
names or raw native error text. Every outstanding object remains pending
on failure; there is no fallback to a full import or Git overwrite.

This repairs the known mapping omission only. Other failures (missing
EDT descriptors after conversion, root XML awaiting treatment,
revision verification or actual merge conflicts) remain unproven until
an explicitly authorized manual build and actual EDT verification.
