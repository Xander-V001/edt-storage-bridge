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
