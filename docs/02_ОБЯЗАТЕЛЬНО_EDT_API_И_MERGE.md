# ⛔ ОБЯЗАТЕЛЬНО ПЕРЕЧИТАТЬ ПЕРЕД КАЖДЫМ ОТВЕТОМ И ДЕЙСТВИЕМ: API EDT, ИМПОРТ, СРАВНЕНИЕ, MERGE

> **Технический справочник:** ветка `feature/edt-2026.2-integration`, код `4fab919` (21.10.2026, до документационных изменений). Текущую готовность и ограничения смотреть в [IMPLEMENTATION_CURRENT.md](IMPLEMENTATION_CURRENT.md), [RECEIVE_PIPELINE_CONTRACT.md](RECEIVE_PIPELINE_CONTRACT.md), [OUTGOING_IMPLEMENTATION_STATUS.md](OUTGOING_IMPLEMENTATION_STATUS.md). Код и конкретное испытание важнее исторического описания.


> **СТОП.** Разработчик не имеет права говорить «EDT не приняла изменения» только потому, что `*.mdo` или `*.distr` отличается ПО БАЙТАМ от независимого импорта XML.
>
> ПЕРЕД КАЖДЫМ ОТВЕТОМ И ДЕЙСТВИЕМ прочитать также [команды 1С](01_ОБЯЗАТЕЛЬНО_1С_КОМАНДЫ_И_ФАЙЛЫ.md), [полный путь данных](03_ОБЯЗАТЕЛЬНО_МАРШРУТ_ДАННЫХ_И_ПРОВЕРКИ.md), [AGENTS.md](../AGENTS.md), текущий исходный код.
>
> **ДОКАЗАТЬ ОТДЕЛЬНО:** результат импорта, жизненный цикл штатного сравнения, факт окончания Merge, пользовательский выбор, применённые объекты, номер версии классического хранилища.

**Область:** ВСЕ непосредственно используемые при получении внешние классы и методы EDT; отдельно отмечен устаревший/подготовленный three-way и outgoing интерфейс. Дата 2026-10-20.

**Маркировка:** `[SDK]` — опубликованный Javadoc EDT **2024.2/2025.2**; `[КОД]` — использован в этом репозитории; `[?]` — бинарная совместимость/семантика **EDT 2026.2 НЕ ПРОВЕРЕНА**. Ссылки — в §12. Описание интерфейсов не означает, что их версия 2026.2 совпадает побайтно с Javadoc 2025.2.

## 1. ТОЧНАЯ ГРАНИЦА ПЛАТФОРМА → EDT

До API EDT:
- `/ConfigurationRepositoryUpdateCfg` обновила **ИБ-посредник**, НЕ рабочий EDT-проект.
- `/DumpConfigToFiles` сделала папку **платформенного XML**, а `-getChanges` — отдельный список имён.
- `ConfigDumpInfo.xml` — индекс, НЕ одна из моделей EDT.

После этого API EDT:
1. `IImportService` конвертирует ПЛАТФОРМЕННЫЙ XML во **ВНЕШНИЙ временный EDT-проект** (`.mdo`, BSL, ресурсы).
2. `IComparisonManager` сравнивает модель активного EDT-проекта с внешним источником.
3. Редактор EDT позволяет человеку выбирать изменения и запускает штатный Merge.
4. **СОБЫТИЕ `MERGE_PROCESS_FINISHED` означает завершение операции Merge, но не «каждую галочку приняли», не «файлы идентичны», не «хранилище равно Git».**
5. Результат класса `ReceiveRevisionStore.REVIEWED` относится к логике плагина и ВНЕШНЕМУ HEAD хранилища; EDT такого файла/версии автоматически не пишет.

## 2. Получение сервисов: ServiceAccess.get(Class<T>)

**Сигнатура:** `ServiceAccess.get(IV8ProjectManager.class)`, `ServiceAccess.get(IImportServiceRegistry.class)`, `ServiceAccess.get(IQualifiedNameFilePathConverter.class)`, `ServiceAccess.get(IComparisonManager.class)`, `ServiceAccess.get(IDtComparisonEditorInputFactory.class)`, а также `IConfigurationProvider`, `IBmModelManager`, `IExportOperationFactory` для других функций.
- `Class<T>`: точный тип зарегистрированного сервиса Eclipse/EDT, не имя проектной конфигурации.
- **Возврат:** runtime-сервис EDT; `get` НЕ выполняет импорт, НЕ открывает редактор, НЕ применяет изменения.
- **[КОД]** `EdtPublicServiceBridge` делает короткоживущие обращения, не создаёт отдельный Guice Injector. Старый собственный injector в EDT 2026.2 вызывал исключения.
- **[?]** Для EDT 2026.2 проверить фактическую доступность сервиса/пакета в установленном релизе. Не использовать reflection/internal вместо проверки контракта.

## 3. IV8ProjectManager.getProject(IProject project)

- **`project`**: EXISTING Eclipse `IProject`, активный проект пользователя, не база 1С, не временная папка XML.
- **Возврат:** `IV8Project active` или отсутствие модели. `active.getVersion()` — версия платформы/проекта для выбора XML-импортёра, **НЕ** версия классического хранилища 29437.
- **[КОД]** `EdtSelectiveReviewService.prepare()` проверяет `project.isAccessible()`, `project.getLocation()`, `active != null`.
- **ДЕЙСТВИЕ:** чтение модели, без Merge/записи версии.

## 4. IImportServiceRegistry.getImportService(active.getVersion())

- **Вход:** `active.getVersion()` — версия 1С-модели проекта EDT; не Git SHA/HEAD хранилища.
- **Выход:** реализация `IImportService`, умеющая конвертировать платформенный XML для этого проекта.
- **[КОД]** вызывается в `EdtSelectiveReviewService.prepare` и в альтернативном `EdtDeltaComparisonService.prepare`.
- Ошибка разрешения импорта → остановить подготовку. **Нельзя выбирать «ближайшую версию» наугад.**
- **[?]** Конкретная реализация, список допустимых форматов, поведение с частичным патчем и ConfigDumpInfo в EDT 2026.2 требуют фактического теста.

## 5. IImportService.work — КРИТИЧЕСКИ ВАЖНЫЕ ПЕРЕГРУЗКИ

**[SDK]** У интерфейса две перегрузки:

```java
IStatus work(IProject project, Path sourcePath,
             IProgressMonitor progressMonitor);
IStatus work(IProject project, Path sourcePath,
             Path destinationPath, IProgressMonitor progressMonitor);
```

| Параметр | 3 аргумента | 4 аргумента (наш безопасный receive) |
|---|---|---|
| `project` | Проект, **в который** импортируются файлы | **Контекст** существующего EDT-проекта, его версия/настройки |
| `sourcePath` | Каталог платформенного XML | Каталог платформенного XML, созданный штатной командой 1С |
| `destinationPath` | Нет | **Отдельная временная папка**, НЕ внутри рабочего Git-проекта |
| `progressMonitor` | Монитор Eclipse, может разрешать отмену | Монитор Eclipse; при остановке импорт НЕ подтверждён |
| **Результат** | `IStatus` | `IStatus` с severity/message/child statuses |

- **[КОД]** `status = importer.work(project, xml, target, monitor)`; требуется `status != null && status.isOK()`. Даже успех означает **только законченный импорт XML во внешний каталог**, НЕ принятие изменений в EDT.
- **[ЗАПРЕТ]** НЕ заменять четырёхаргументную перегрузку трёхаргументной: последняя импортирует в открытый проект напрямую, в обход пользовательского Compare/Merge.
- **ВЫХОД:** файлы EDT во временном `target`, обычно `target/src/...`, в частности `.mdo`, `.bsl`, формы, шаблоны, бинарные ресурсы и служебные файлы модели. Набор зависит от объекта/версии импортёра. **Это уже не XML платформы.**
- **ЗАМЕЧАНИЕ:** не сравнивать EDT-модель с `ConfigDumpInfo.xml` по методу `Files.mismatch`; это разные форматы и смысл.
- **[?]** Встроенные папки/ресурсы и редакторные преобразования могут отличаться в EDT 2026.2; сравнивать модели штатным сравнением, а не объявлять любое отличие `.mdo` непринятым изменением.

## 6. Временный источник данных EDT: каждый конструктор

**[SDK/КОД]**
- `new V8ProjectComparisonDataSourceDescriptor(active)`:
  - `active` — `IV8Project` реального рабочего проекта; **главная сторона MAIN**, то, что может меняться после Merge.
- `new FileSystemComparisonDataSourceDescriptor(target, project.getName())`:
  - `target` — каталог СКОНВЕРТИРОВАННОГО во временную EDT-модель XML;
  - `project.getName()` — имя/контекст модели источника;
  - это **OTHER/входящая сторона**, не новая подключённая ИБ и не Git-копия.
- `new GitComparisonDataSourceDescriptor(root, ...)`:
  - в исходниках `EdtDeltaComparisonService` сохранён вариант ТРЁХСТОРОННЕГО сравнения с Git baseline;
  - **НЕ ПУТАТЬ** с фактическим двухсторонним путём `EdtSelectiveReviewService`. SHA Git не равен опорной ревизии 1С.

**[ЗАПРЕТ]** Временный источник обязан находиться ВНЕ `project/src` и Git; путь не должен через symlink вести внутрь проекта. Не очищать staging, пока native compare/merge ещё использует его.

## 7. ComparisonScope — что именно EDT сравнивает

**[SDK]** `ComparisonScope` содержит списки **EDT symbolic links** верхнеуровневых объектов для каждой стороны, а НЕ имена файлов `changes.txt` и НЕ список `objects.txt`.
- `new ComparisonScope(requestedScope, requestedScope)` — двухсторонний selective. **Код обязан предварительно преобразовать** имена 1С в реальные EDT symbolic links и подтвердить наличие владельцев.
- `ComparisonScope.EMPTY_SCOPE` — в используемой схеме **GLOBAL** (стороны перечисляются механизмом EDT). Это трактовка, использованная `EdtSelectiveReviewService.globalScope()`; убедиться по фактической версии EDT, не подменять ручным списком всех `.mdo`.
- `new ComparisonScope(main, other, ancestor)` — three-way; в `EdtDeltaComparisonService` есть отдельный, НЕ основной receive.
- При пустой выборке без подтверждённого глобального режима **не пытаться «улучшить» её автоматической полной выгрузкой**.

## 8. ComparisonProcessHandle и CompareMergeProcessDescriptor

```java
var handle = new ComparisonProcessHandle(
    new V8ProjectComparisonDataSourceDescriptor(active),
    new FileSystemComparisonDataSourceDescriptor(target, project.getName()),
    comparisonScope);
return new CompareMergeProcessDescriptor(handle, settings);
```
- `handle`: идентифицирует пару/тройку источников + область сравнения. **НЕ** является результатом слияния и НЕ содержит выбора галочек.
- `settings`: `ComparisonProcessSettings`, настройка логики сравнения/объединения.
- **СИГНАЛ УСПЕХА:** сам факт существования дескриптора означает только **подготовку редактора**.

### Параметры ComparisonProcessSettings (КОД)

```java
ComparisonProcessSettings.builder(MatchingStrategy.UUID_THEN_NAME)
 .parseBslModuleStructure(true)
 .mainSideObjectsDeletionAllowed(true)
 .build();
```
- `MatchingStrategy.UUID_THEN_NAME`: сначала идентичность объекта, затем имя, при сопоставлении моделей. **Не значит** «копировать байты», «найти Git baseline».
- `parseBslModuleStructure(true)`: разбирать структуру BSL для сравнений/слияния модулей; пользователь может выбирать на уровне частей/процедур.
- `mainSideObjectsDeletionAllowed(true)`: разрешает **выбираемое пользователем** удаление объектов MAIN в процессе merge; не удаляет сам по себе.
- `build()`: создаёт неизменяемую конфигурацию процесса, не запускает EDT job.
- **[?]** Точная семантика новых флагов/дефолтов 2026.2 должна сверяться с его SDK/binary, не изобретать флаги по старому Javadoc.

## 9. IDtComparisonEditorInputFactory и открытие native EDITOR

**[КОД]** `EdtDeltaComparisonService.openEditor(descriptor, inputFactory)` и `openReadOnlyEditor`:
- `descriptor` — подготовленный процесс сравнения (не применённые объекты).
- `inputFactory` — сервис создания native editor input.
- EDT Workbench `IWorkbenchWindow` / `IWorkbenchPage.openEditor(input, id)` открывают интерфейс выбора.
- UI-вызов только в SWT UI thread. Закрытие редактора (`IPartListener.partClosed`) **НЕ** равно «объединение завершено».
- Текущая логика подготавливает процесс до открытия; подписку на события Merge нужно зарегистрировать **ДО** открытия редактора.

## 10. IComparisonManager — КАЖДЫЙ ИСПОЛЬЗУЕМЫЙ МЕТОД И ПАРАМЕТР

**[SDK]** `IComparisonManager` — менеджер АСИНХРОННЫХ процессов сравнения/слияния моделей EDT. Предоставляет session по handle, управляет статусами.

| Метод/аргументы | Возвращает/эффект | Ограничение |
|---|---|---|
| `addStatusListener(listener)` | Регистрирует подписку на события; ничего не мержит | Зарегистрировать до открытия редактора |
| `removeStatusListener(listener)` | Удаляет подписку | В `finally`, не терять ошибку Merge при сбое удаления |
| `getStatus(handle)` | `ComparisonProcessStatus` или `null` | `null` означает неизвестный/удалённый процесс, НЕ «успех»/«отмена» |
| `getComparisonSession(handle)` | `IComparisonSession` | Только пока сессия существует; после Merge может быть удалена |
| `startComparison(batch)` | Запускает асинхронное сравнение | Сам факт запуска не означает COMPARISON_PROCESS_FINISHED |
| `startMerge(batch,monitor)` | Планирует асинхронный Merge, `IStatus` о планировании | `Status.OK` **не** означает завершение Merge |
| `startMergeIgnoringProblems(batch,monitor)` | Вариант небезопасного обхода проблем | В receive **не использовать** |
| `cancel(handle)` | Отменяет сравнение до merge при поддерживаемом состоянии | Не обещает откат уже выполненного Merge |
| `stop(handle)` | Закрывает процесс/сессию | Не доказательство принятия изменений |
| `getMergeProblems(handle)` | Список проблем проверки/внешнего merge | API разрешает вызов лишь в определённых статусах |
| `hasActiveComparison()` | Признак активного сравнения | Не означает конкретный handle |
| `getHandles(v8Project)` | Ручки процессов для проекта | Не является журналом версии хранилища |

**[КОД]** В штатном receive плагин НЕ вызывает `startMerge` напрямую: редактор EDT осуществляет работу; `NativeEdtMergeWitness` только наблюдает через `addStatusListener`, `getStatus`, `removeStatusListener`. Передавать процессу произвольный batch вместо EDT UI — другой контракт.

### Listener: обе сигнатуры
- `statusChanged(ComparisonProcessHandle updated, ComparisonProcessStatus status)`: статус **конкретного handle**; наш witness учитывает только `handle.equals(updated)`.
- `statusChanged(CompareMergeProcessBatch batch, CompareMergeProcessBatchStatus status)`: статус батча; наш witness **не** принимает батч за подтверждение конкретного Merge.

## 11. ComparisonProcessStatus — НЕ ПУТАТЬ С ПРИНЯТИЕМ ИЗМЕНЕНИЙ

| Статус | Значение по SDK | Что разрешено |
|---|---|---|
| `COMPARISON_PROCESS_INITIALIZATION_STARTED` | Начало сравнения | Не фиксировать |
| `COMPARISON_PROCESS_INITIALIZATION_FINISHED` | Инициализация закончена | Не фиксировать |
| `COMPARISON_PROCESS_TOP_OBJECTS_MATCHED` | Сопоставлены верхнеуровневые объекты | Не фиксировать |
| `COMPARISON_PROCESS_FINISHED` | Сравнение завершено | Это **НЕ Merge** |
| `MERGE_PROCESS_STARTED` | Merge начался | Нельзя закрывать staging преждевременно |
| `MERGE_PROCESS_USING_EXTERNAL_TOOL_STARTED` | Начата внешняя часть | Не фиксировать |
| `MERGE_PROCESS_USING_EXTERNAL_TOOL_FINISHED` | Внешняя часть закончена | Не означает всего Merge |
| `MERGE_PROCESS_USING_EXTERNAL_TOOL_CANCELED` | Внешняя часть отменена | Не записывать успех без ранее подтверждённого завершения |
| `MERGE_PROCESS_VALIDATION_STARTED` | Начало валидации | Не фиксировать |
| `MERGE_PROCESS_VALIDATION_FINISHED` | Валидация закончена | Не равно выполнению |
| **`MERGE_PROCESS_FINISHED`** | **Merge закончен после post-merge действий** | Можно переходить к проверке версии/границ; не знает галочек |
| `MERGE_PROCESS_DISCARDED` | Источники закрыты, сессия завершена | Это НЕ отмена уже законченного Merge |
| `COMPARISON_MERGE_PROCESS_CANCELLED` | Сравнение/слияние отменено | Не подтверждать REVIEWED без иных свидетельств |

**[КОД: NativeEdtMergeEvidence]** сохраняет факт `finished`, `cancelled`, `mergeStarted`, `discarded`. Поздний `DISCARDED` **не затирает** ранний `FINISHED`. `awaitAfterEditorClose(graceMilliseconds,mergeTimeoutMilliseconds,canceled)`: ожидание только background job; `graceMilliseconds` — краткое ожидание старта асинхронного merge; `mergeTimeoutMilliseconds` — максимум ожидания начатого; `canceled` — флаг отмены. **Не запускать ожидание в SWT-потоке.**

## 12. Файлы EDT и ошибки сравнения — КАЖДЫЙ смысл отдельно

| Ресурс | Пример | Кто создал | Как проверять |
|---|---|---|---|
| Платформенный XML | `Document.xml` и вложенные XML/модули | **1С Конфигуратор** | Структура XML/список, до импорта EDT |
| Временная модель EDT | `src/Documents/Payroll/Payroll.mdo`, `.bsl`, формы, ресурсы | **IImportService.work (4 args)** | EDT native model comparison/merge |
| Рабочая модель EDT | `project/src/...` | **EDT** после Merge | Штатный результат Merge, Git status для диагностики |
| `Configuration.distr` | 1С-пример: отличие на байте 7 051 332 | импорт EDT vs рабочая модель | **Различие байтов НЕ доказательство непринятой конфигурации** |
| `ExchangePlans/МиграцияПриложений.mdo` | отличие на байте 1446 | импорт EDT vs рабочая модель | То же |
| `edt-storage-merge-review-*.txt` | отчёт нашего сравнения | **ПЛАГИН**, не native EDT | Только вспомогательная диагностика, НЕ выбор пользователя |
| `configVersion` в `ConfigDumpInfo.xml` | opaque token | **1С Конфигуратор** | Не числовой HEAD хранилища |
| `REVIEWED/APPLIED/PENDING/KEPT_LOCAL` | preferences вне Git | **ПЛАГИН** | Отдельные доказательства; EDT напрямую их не записывает |

**[ОПЫТ 2026-10-20, 29437]** после выбора всех изменений в EDT плагин побайтно проверил 6121 владельца, совпали 6119. Отличались `Configuration.distr` и `МиграцияПриложений.mdo`, `PENDING=0`. Это **не доказало**, что пользователь отклонил эти объекты. Окно «проект не совпадает с хранилищем» было логически необоснованным.

**[ЗАПРЕТ]** НЕ создавать `KEPT_LOCAL` из одного `Files.mismatch` двух разных EDT-каталогов. Нужен явный выбор пользователя/достоверные записи Merge. Не заявлять, что после native FINISHED все объекты стали побайтно равны.

**[КОД 21.10.2026, `4fab919`]** Повторный `ListSelectionDialog` **отключён** (`LEGACY_KEEP_LOCAL_DIALOG_ENABLED=false`). `NativeEdtMergeChoiceObserver` читает нативную `IComparisonSession` и `MergeSettings` в read-only BM task, фильтруя только реально отличающиеся узлы `hasDiffsMainOther()`. `Snapshot.classifyPending` на основе `[x] GetFromOther` либо изменённого `[ ]/DoNotMerge` группирует принятые и оставленные локально решения **по идентификаторам изменений 1С**, а неопределённые оставляет `unclassified`. После подтверждённого native Merge и проверок Git/HEAD/ИБ `ReceiveCompletionRecorder.reviewedNativeObserved` записывает результат. `APPLIED` не повышается. **[?]** Этот новый код ещё не испытан в установленной EDT. Хранимая классификация не управляет последующим быстрым получением и помещением, не ведёт историю каждой процедуры.

## 13. Дополнительные EDT API подготовки публикации (НЕ ПРИМЕНЯТЬ В RECEIVE)

- `IBmModelManager`: доступ к BM-модели проекта — не XML выгрузка платформы.
- `IExportOperationFactory`: построение штатной выгрузки EDT в XML; служит на пути *EDT → 1С*, а не входящему Merge.
- `IQualifiedNameFilePathConverter`: преобразование квалифицированных имён EDT/путей; **не** принимает как есть русские строки `changes.txt` без проверки.
- `EdtNativeOutgoingExportService.preview(...)` и `previewMetadataOwners(...)` — отдельный путь read-only подготовки отправки, **не** свидетельство работоспособности получения.
- `EdtDeltaComparisonService.prepare(... GitComparisonDataSourceDescriptor ...)` — альтернативная THREE-WAY подготовка; фактический receive вызывает `EdtSelectiveReviewService.prepare()`, TWO-WAY.
- Точное поведение конструктора и экспортных методов для EDT 2026.2 **не подтверждено**. Никаких реальных записей в 1С/хранилище без отдельного разрешения.

## 14. Перед ЛЮБЫМ ответом: контрольные вопросы

1. Где были созданные данные: платформа XML, временная EDT модель, рабочий EDT проект?
2. Какова **точная перегрузка** импортёра? 4 аргумента — ДА, 3 аргумента — НЕЛЬЗЯ.
3. Сравнение TWO-WAY или THREE-WAY? Какая база у каждой стороны?
4. `ComparisonScope` содержит EDT symbolic links или просто имена платформы?
5. Что означают флаги настроек — меняют сравнение или автоматически пишут проект?
6. Событие получено для правильного `handle`, `MERGE_PROCESS_FINISHED` было на самом деле?
7. Откуда вытекает вывод «взял все галочки»? Из API выбора, подтверждения пользователя или выдуман из байтов?
8. Проведён ли отдельный контроль HEAD классического хранилища?
9. Что именно записывается: REVIEWED или APPLIED; почему?
10. Какие утверждения подтверждены **на EDT 2026.2**? Для остальных писать **[?]**.

## 15. Опубликованная документация и версия

- Javadoc IComparisonManager, 2025.2:
  https://edt.1c.ru/dev/edt/2025.2/apidocs/com/_1c/g5/v8/dt/compare/core/IComparisonManager.html
- Javadoc package compare/core, 2025.2:
  https://edt.1c.ru/dev/edt/2025.2/apidocs/com/_1c/g5/v8/dt/compare/core/package-summary.html
- Javadoc IImportService, 2024.2:
  https://edt.1c.ru/dev/edt/2024.2/apidocs/com/_1c/g5/v8/dt/import_/IImportService.html
- Javadoc CompareProcessStatus, 2024.2:
  https://edt.1c.ru/dev/edt/2024.2/apidocs/com/_1c/g5/v8/dt/compare/core/ComparisonProcessStatus.html
- Javadoc ImportXmlService, 2025.2:
  https://edt.1c.ru/dev/edt/2025.2/apidocs/com/_1c/g5/v8/dt/internal/import_/xml/ImportXmlService.html

**[?] ВАЖНО:** Javadoc 2024.2/2025.2 — первичный ориентир на имена и контракт, но **не официальная верификация EDT 2026.2**. Проверять именно бинарники/SDK EDT 2026.2 перед объявлением полной совместимости.
