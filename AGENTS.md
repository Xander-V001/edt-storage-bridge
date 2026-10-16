# Project rules — EDT Storage Bridge

## READ FIRST — до любой работы с плагином (ОБЯЗАТЕЛЬНО)

**Перед любым предложением, проектированием, написанием или изменением кода в этом репозитории сначала перечитать [разбор ошибок и исходную задачу](docs/LESSONS_LEARNED.md), затем текущие `AGENTS.md` и фактический исходный код.** Не полагаться на прошлые обещания, дорожные карты или слова «готово».

Это не пожелание, а условие работы над проектом:

- **Единственная ближайшая цель — работающее быстрое и безопасное обновление существующего проекта 1С:EDT 2026.2 из классического хранилища.** Не отвлекаться на помещение изменений, новую инфраструктуру, интерфейс или очередную перестройку архитектуры, пока цепочка получения не доказана.
- **Реальные участники:** существующее классическое хранилище 1С; **одна уже существующая ИБ, привязанная к хранилищу, но никак не связанная с EDT**; один существующий проект EDT с локальным Git (`main`, одна ветка на задачу). К проекту EDT может быть подключено множество **других** ИБ — ни одну из них нельзя произвольно использовать как посредника.
- **Не создавать** вспомогательные ИБ, проекты EDT, Git-ветки/worktree или другие сущности без доказанной технической необходимости и явного согласия. Не выполнять полную выгрузку/импорт всей большой конфигурации при каждом обновлении: нужен **инкрементальный обмен изменёнными объектами**.
- **Пример, на котором всё проверяется:** у разработчика закоммичен собственный `Документ1` в `main`; хранилище перешло с версии 100 на 101. Обновление должно предложить изменения на сравнение, позволить оставить свой `Документ1` и принять остальные объекты. Пропущенный `Документ1` остаётся неразрешённым расхождением; не объявлять проект целиком синхронизированным с 101.
- **Нельзя** называть механизм «реализованным», пока не проверены вызовы EDT 2026.2, объектное сравнение/объединение и тестовое обновление без потери коммитов. Каркас команды, экспорт XML, документ или кнопка — не готовое получение.
- **Перед новым изменением:** назвать конкретный недостающий шаг на критическом пути и проверить, что он опирается на существующие 1С/EDT механизмы. **После:** указать реально изменённые файлы/коммит и честно назвать непроверенное. Никаких противоречащих друг другу прототипов и переписываний дорожной карты вместо работающего кода.

Текущий статус на 2026-10-16: **обновление EDT из хранилища НЕ РАБОТАЕТ**. В исходниках есть настройки, чтение истории и заготовка выборочной выгрузки; отсутствуют рабочий контроллер применения в EDT и проверенное объединение. Кнопка получения отключена. Последняя успешная CI-сборка — более старый код. База-посредник с EDT не связана.

---

These rules are binding for all development in this repository and chat.

## CI resources
1. **Never start a build or tests after every commit.** GitHub Actions build/test workflows must use `workflow_dispatch` only. No automatic builds on push, pull requests, merging, cron schedules, release events, or branch changes.
2. **Do not manually dispatch a build without an explicit user request for that specific build.** No hidden or duplicate builds. Reviewing Java/XML, creating commits, PRs or merging must not trigger compilation.
3. **Avoid artifact accumulation.** After every manually requested successful build, delete obsolete artifacts from earlier builds while retaining one newest `edt-storage-bridge-p2` archive and one newest diagnostics artifact (if any). Newly uploaded artifacts expire after 3 days. A separate **manual** `Cleanup old build artifacts` workflow handles already accumulated artifacts without compiling code.
4. Cleanup is scoped to artifacts named `edt-storage-bridge-p2` and `tycho-test-reports`; never delete unrelated project artifacts or GitHub releases.
5. Keep GitHub Actions time usage minimal. Avoid duplicate jobs or matrix builds until explicitly approved.

## Target platform
6. **Primary and only development target: 1C:EDT 2026.2**, using Eclipse 2025-12 and Java 25. Do not target EDT 2026.1 or older without the user's explicit request.
7. Before any 2026.2 release, prove target resolution, compilation and installation with a user-approved manual build; old Eclipse baseline success does not count.

## Safety
8. Never use the user's VPS to build, test, host, or run this project without explicit permission.
9. Never write to a working 1C infobase or configuration repository for development/testing. Only isolated disposable test data may be used with explicit agreement.
10. No credentials, session tokens or local machine secrets in commits, build logs, or artifacts.
11. Never label a plugin as tested in a 1C:EDT application until it has actually been installed and smoke-tested in that version.
12. Treat repository state, staging base state, EDT project state, and Git state as separate; verify results before advancing checkpoints.

## Code and workflow
13. Prefer supported, versioned EDT/Eclipse APIs. No reflection/internal Eclipse/EDT shortcuts without a documented necessity and test plan.
14. Keep exchange business logic independent from Eclipse UI and Git.
15. Document changed behavior, expected invariants and known gaps in `docs/`. New API integrations require read-only proof before implementation of write operations.
16. Development can be committed without running a build; clearly state that new changes remain unverified until the user requests a build.

## Repository user experience and state
17. Right-clicking a **project** in EDT should show a direct `Работа с хранилищем` command opening its own dialog. Settings must not be global to all projects.
18. Persist nonsecret settings in workspace-scoped preferences, not project content or Git. Encrypt the repository password with Eclipse Secure Storage.
19. Distinguish actual repository current/observed version from the last repository version **verified as applied** to EDT. Neither may be invented; never increment on merely saving settings or running a subprocess.
20. **Do not require an intermediary 1C infobase for read-only access by default.** Investigate direct supported read access. Use an isolated connected infobase only for operations where a supported mechanism requires it and explain the tradeoffs.

## Local Git task model
21. The **user's EDT configuration project** has its own local Git repository, distinct from this plug-in's GitHub repository. Use its Git history as an essential source of task changes. **One task = one feature branch**; do not treat a branch as a storage version.
22. Before pushing to classic 1C storage, display a confirmed **selection of unpublished task commits** and translate their cumulative diff into configuration object changes. Do not automatically upload all modifications in HEAD. In v1 permit only a contiguous, unambiguous range with verified ancestry and dependency checks.
23. **No technical Git branches/worktrees** may be created for storage synchronization. User branches are only `main` and the developer's task branches. Stage incoming repository changes in disposable non-Git files/model or an explicitly approved staging base; show an obligatory compare/merge before applying to the active project. Never auto-checkout, reset, force-push, rebase or merge over active EDT work.
24. Track the last applied **storage revision and Git baseline SHA per project and task branch**. Current project-only revision key is inadequate for multi-branch workflows and must be migrated before transfer actions are enabled.
25. Never promise Git commit == one classical repository revision. Persist traceable commit-to-storage mappings only after actual write verification; failed/partial operations must not mark commits as published.
26. Selectable object-level receive must track explicitly skipped/rejected remote changes. Partial selection must never mark the entire repository revision as fully applied. Do not reuse earlier stage-dependent prototype on a working base until selective comparison is proven.
