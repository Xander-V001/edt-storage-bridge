# Dialog: «Работа с хранилищем»

**Target:** 1C:EDT 2026.2. **Status:** source only — these latest dialog changes have not been compiled or smoke-tested. The successful CI run #38938763356 predates this UI.

## Open

Right click on the workspace project in EDT Navigator → **Работа с хранилищем**. The dialog applies only to that project.

### Configuration fields

| Group | Field | Purpose |
|---|---|---|
| Хранилище | Адрес хранилища | Connection address of classic configuration repository |
| Хранилище | Пользователь | Repository account |
| Хранилище | Пароль | Encrypted in Eclipse Secure Storage, never stored in project files |
| Подключённая ИБ 1С (необязательно) | Тип ИБ | Файловая (`/F`) or Серверная (`/S`) |
| Подключённая ИБ 1С (необязательно) | Каталог ИБ / сервер/имя | File base directory containing `1Cv8.1CD`, or `server/ibName` |
| Подключённая ИБ 1С (необязательно) | Путь к платформе | Path to installed `1cv8.exe` of the platform version authorized for the storage |
| Состояние | Версия хранилища | Last **confirmed** remote observed revision |
| Состояние | Версия проекта EDT | Last **verified successfully applied** revision |

The file base directory and Windows executable may be selected with **Обзор...**. For server bases, type the cluster/infobase address. Infobase/platform values are **optional**: you can leave both blank for a future direct-read-only transport.

The dialog currently only saves and loads fields — **no 1C Designer process or storage command is executed**, there is no connection test, no update, and no commit. Settings do not prove the infobase is connected to that repository. Do not display stale versions as current values. A change to the repository address invalidates the version checkpoints.

## Why use the existing connected infobase?

The official 1C methodology calls a connected infobase the primary mode for group development, where capture, receive and commit adhere to the platform's locking and object dependency rules. It also warns that one repository user has one bound infobase identity; forcibly rebinding another base can break the original connection.

For our plugin, reusing an existing dedicated connected base can avoid provisioning a new disposable infobase and provide a supported Designer-command execution context. We should prefer it for **operations requiring classical repository semantics**, after validating identity, user, platform version and current captures. It is **not** proof that we may modify the user's working base without a separate explicit authorization. Read-only history reports may be run through the base only after reviewing their side effects and connection lock implications.

A *direct read-only* adapter (e.g., supported `v8storage` integration) remains an alternative for querying revision/history without using an infobase. Choosing transport per action is an implementation decision, not exposed as complexity in the main dialog.

**Official sources:**
- Connected vs unconnected mode, bound user and object-locking semantics: https://its.1c.ru/db/content/metod8dev/src/developers/platform/metod/devtools/i8102287.htm
- Designer repository report/update/dump commands: https://kb.1ci.com/1C_Enterprise_Platform/Guides/Administrator_Guides/1C_Enterprise_8.3.27_Administrator_Guide/Appendix_7._Startup_command-line_options_of_1C_Enterprise/7.4._Running_Designer_in_batch_mode/7.4.15._Configuration_repository_operations/
- File `/F` and server `/S` parameters: https://kb.1ci.com/1C_Enterprise_Platform/Guides/Administrator_Guides/1C_Enterprise_8.3.27_Administrator_Guide/Appendix_7._Startup_command-line_options_of_1C_Enterprise/7.3._General_startup_commands/7.3.1._Connection_parameters/

## Storage and security

All non-secret fields reside in Eclipse workspace instance preferences **per project**, keyed by SHA-256 of its project location URI. Passwords go through Eclipse Secure Storage, with encryption failure blocking save. No credentials, local file paths, or version checkpoints are written into the source project/Git.

For future operations, infobase credentials (if needed) must be handled *separately* from repository credentials. No password arguments may be echoed into logs.

## Version invariants

- Observed remote version is updated only after a genuine authenticated read from the correct storage.
- Applied EDT version is updated only after the imported changes have been verified in the EDT model.
- Version changes are **not** calculated using plus-one; numbers belong to the storage history.
- Saving form values, dumping a .cf, executing Designer or committing Git does not itself advance the applied checkpoint.

## Next permitted steps

1. User-authorized **manual** build against EDT 2026.2.
2. Install and test project context menu/dialog in real EDT.
3. Validate file/server path round-trip and Eclipse Secure Storage, including reopen and errors.
4. Build a read-only connection/version/status probe against a **disposable test repository/infobase**, compare direct access and Designer-based reporting.
5. Later: explicit safety confirmation before accessing the existing connected base (identify whether it is dedicated, has other users or captured objects, and whether operations can alter it).

Never assume that a user's permission to store the base path is permission to launch Designer, receive/update configurations or modify the base.
