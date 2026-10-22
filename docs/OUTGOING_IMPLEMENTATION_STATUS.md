# Помещение изменений EDT → классическое хранилище 1С — код и ограничения

**Срез реализации:** 21.10.2026, `feature/edt-2026.2-integration`, исходники `4fab919` до документационных изменений. **TEST-ONLY**: здесь описана реально написанная опасная операция. Работу в установленной EDT и на тестовом хранилище не доказали. Никаких запусков в рабочую ИБ.

## Что фактически существует [КОД]

1. `OutgoingReadinessHandler` / `OutgoingReadinessCoordinator`: проверка Git, `ReceiveRevisionStore.reviewed`, `ReceivePendingStore`, текущего HEAD хранилища; при несовпадении предлагает пройти получение. Проверка — **не** автоматическое разрешение на помещение.
2. `OutgoingReadOnlyComparisonCoordinator`: сравнение проекта EDT с временной EDT-моделью посредника в **неизменяющем** режиме. **После исправления 22.10.2026:** прежний файловый отбор `OutgoingDeltaScanner.compare()`, `OutgoingDifferenceSummary` и предварительное окно **исключены из пути помещения**. Перед открытием редактора подписываемся на штатный `IComparisonManager`, на `COMPARISON_PROCESS_FINISHED` читаем `IComparisonSession.getRootNode()` через `runComparisonTreeReadonlyTask`. Различия определяет **только `ComparisonFlags.hasDiffsMainOther()`**. Нативные идентификаторы преобразуются публичным `IQualifiedNameFilePathConverter`. Отдельная фабрика `OutgoingMetadataObjectPlan.fromNativeChangedOwners` получает лишь подтверждённые объекты; никакой `Files.mismatch()` не участвует.
3. `OutgoingMetadataExportCoordinator.preview`: после review разрешает имена через штатные EDT-сервисы и вызывает `EdtPublicServiceBridge.previewNativeMetadataExport` / `EdtNativeOutgoingExportService` / `IExportOperationFactory` + `IExportStrategy.DEFAULT`. Экспортирует целые владельцы вместе с зависимыми файлами в отдельный каталог. Превью и XML **сами по себе не доказывают** допустимость Load/Commit.
4. `OutgoingWholeObjectWritePlan.qualify`: сверяет exportable owners с native names и реальными файлами, требует корневые `<owner>.xml`. Для добавления/удаления включает `Configuration/Configuration` → `Configuration.xml`; **раздельно** формирует Lock scope и Commit scope. При удалении `Configuration` захватывается с `includeChildObjects=true`, что требует особой квалификации риска.
5. **Только после двух явных подтверждений** (ввод `ТЕСТ` и отдельное подтверждение записи) `OutgoingWholeObjectWriteCoordinator.confirmTestWrite` создаёт журнал, повторно экспортирует, проверяет Git/HEAD/версию посредника и выполняет:
   - `OutgoingDesignerCommands.Stage.LOCK`: `/ConfigurationRepositoryLock -Objects <lock-objects.xml>`;
   - `Stage.LOAD`: `/LoadConfigFromFiles <exportRoot> -Format Hierarchical -listFile <load-list.txt>`;
   - `Stage.COMMIT`: `/ConfigurationRepositoryCommit -Objects <commit-objects.xml> -comment "EDT Storage Bridge: metadata objects"`.
   **Важно:** код в текущей ветке **не** передаёт отдельный ключ `-partial`; не писать, что он есть в argv. Частичность выбирается списком `-listFile`; её совместимость с реальной платформой ещё проверить.
6. После Commit повторно читается настоящий HEAD. При ожидании `reviewedRevision + 1` отмечает журнал `COMPLETED`, сохраняет `OutgoingPublishCheckpoint`/Git привязку. Обработанность incoming `REVIEWED` и исходящая публикация **не одно и то же**.

## Данные, хранилище, источник

```text
проект EDT + Git
  → native read-only diff с конфигурацией посредника
  → владельцы изменённых объектов (+ Configuration root для add/delete)
  → штатный EDT native XML export во временный каталог
  → Lock выбранных классических объектов
  → LoadConfigFromFiles в *существующую* базу-посредник
  → Commit в классическое хранилище
  → повторный HEAD и локальная отметка публикации
```

`lock-objects.xml` и `commit-objects.xml` — **XML-перечни классических объектов** в пространстве `http://v8.1c.ru/8.3/config/objects` (`OutgoingStorageObjectsXml`), с `includeChildObjects`. `load-list.txt` — **пути файлов XML-выгрузки**, UTF-8 с BOM и CRLF. Подставить `-Objects` вместо `-listFile` или наоборот нельзя.

`OutgoingWholeObjectWritePlan` использует корневые XML метаданных в `-listFile`, поскольку полная структура экспортированного объекта лежит рядом в каталоге. Добавления и удаления верхнего уровня требуют родительского `Configuration.xml` и специального захвата; **формальная поддержка кодом не равна проверенной корректности на реальной конфигурации**.

## Защита, реализованная кодом

- Настройки должны идентифицировать хранилище/ИБ; локальный Git и ветка зафиксированы, нельзя незаметно подменить источник; перед Lock и Commit проверяется HEAD.
- `ReceiveRevisionStore.reviewed`, `ReceivePendingStore`, `ReceiveBaselineJournal`: проверяется отсутствие непринятых inbound изменений, совпадение полученного/просмотренного HEAD с реальным хранилищем. Нет кнопки «игнорировать несовпадение».
- `ConfigVersionSnapshotRunner` сравнивает полный native `ConfigDumpInfo` посредника с ранее зафиксированным; нельзя считать произвольную ИБ тождественной хранилищу.
- `OutgoingWholeObjectWritePlan.verifyFiles` отвергает недостающие ресурсы/symlink/плохую область; выбранные владельцы перепроверяются после нового экспорта.
- `OutgoingOperationJournal`: `PREPARED → LOCK_UNCERTAIN → LOCK_PROCESS_OK → LOAD_UNCERTAIN → LOAD_PROCESS_OK → COMMIT_UNCERTAIN → COMMIT_PROCESS_OK → COMPLETED`. Marker `active.txt` блокирует новый запуск после неопределённого исхода; поднимать новую версию без фактического HEAD запрещено.
- `OutgoingDesignerRunner` запускает `ProcessBuilder`, не shell, ограничивает время и объём логов, маскирует пароли/адреса в сохраняемых копиях. Native stage может изменить базу ещё до исключения/отмены.
- Нет автоматических `-force`, `UnLock -force`, сбросов Git или повторов COMMIT.

## Использование решений наблюдателя при помещении

**[КОД] Сейчас не используется.** При получении `NativeEdtMergeChoiceObserver` пишет выбранное локальное состояние через `ReceivePendingStore` (решение `KEPT_LOCAL` либо принятие). Но `OutgoingReadinessCoordinator`, `OutgoingReadOnlyComparisonCoordinator`, `OutgoingWholeObjectWriteCoordinator` читают `REVIEWED` и `PENDING`, **не читают `KEPT_LOCAL`**. Кандидаты к помещению определяются независимо через Git/native read-only EDT comparison. Пометка о локально оставленном изменении сама по себе не добавляет объект или конкретную процедуру в публикацию.

**Связь со следующим получением (код после `4fab919`):** при непустом `KEPT_LOCAL` `ReceiveChangesCoordinator` до обновления ИБ **не продолжает быстрый приём**, а предлагает полноценную сверку с глобальным native EDT compare. Отмена ничего не меняет. После штатного успешного FULL Merge прежние метки пересчитываются по отличающимся узлам, лишние локальные разработки в `KEPT_LOCAL` не добавляются, снятые решения удаляются, оставленные локально остаются. **Это относится к получению, а не к исходящему помещению**, которое по-прежнему не читает `KEPT_LOCAL`.


## Ручное помещение — настройка проекта [КОД, НЕ ИСПЫТАНО]

В `StorageSettingsDialog` → **«Помещение в хранилище»** есть флажок
**«Ручное помещение через Конфигуратор 1С»**. Он сохраняется отдельно
для проекта EDT в `StorageProjectSettings.manualOutgoing()`.
**По умолчанию выключен**: при выключенном флажке после общего preview
остаётся прежнее предложение экспериментального автоматического
`Lock → Load → Commit`. При включённом вместо него вызывается
`OutgoingManualHandoffCoordinator`, без автоматического помещения.

Общий read-only diff EDT и нативный EDT XML-экспорт не меняются.
После успешного экспорта и квалификации области:
1. Перед подготовкой повторно проверяются Git, `REVIEWED/PENDING`,
   native HEAD, полный индекс `ConfigDumpInfo` посредника и XML-файлы.
2. В существующей базе-посреднике выполняется **только** `/DumpCfg`
   для сохранения `baseline.cf`.
3. `1cv8 CREATEINFOBASE File="..." /UseTemplate baseline.cf` создаёт
   **отдельную временную файловую ИБ**, не регистрируя её в списке баз.
4. `/LoadConfigFromFiles -Format Hierarchical -listFile` импортирует
   XML изменений EDT **исключительно в эту временную ИБ**.
   Из неё `/DumpCfg` формирует полный `candidate.cf`.
5. Git, HEAD и индекс исходной ИБ повторно проверяются. Кандидат
   остаётся по адресу
   `<state>/outgoing-manual-handoff/<UUID>/candidate.cf`.
   Плагин предлагает запустить обычный Конфигуратор исходной ИБ.
   Пользователь сам выбирает **«Конфигурация → Сравнить, объединить
   с конфигурацией из файла»**, указывает `candidate.cf`, отмечает
   нужные изменения, выполняет захваты и помещение в хранилище.

**Граница:** плагин автоматически открывает только Конфигуратор,
а не недокументированный интерактивный режим сравнения через CLI.
В этом сценарии он **никогда не вызывает Lock, Load или Commit
для исходной базы-посредника** и **не помечает ручное помещение
опубликованным**. После ручного Commit необходима отдельная сверка
версии/состава, автоматического зачёта пока нет.

`baseline.cf`, `candidate.cf`, временная ИБ и служебные логи
сохраняются на диске (могут содержать метаданные и занимать много места).
Плагин автоматически их не удаляет.

**Обновление 22.10.2026:** устранена ошибочная зависимость от
OSGi-регистрации `IExportOperationFactory` (коммит `ce06f5e`).
Плагин получает фабрику через существующий Guice-инжектор
экспортного бандла EDT, без собственного инжектора. **Это изменение
исходников, а не подтверждённое runtime-испытание в EDT 2026.2**.
Если встроенная фабрика экспорта не инициализируется, операция прерывается
до записи в ИБ/хранилище. Нативный XML round-trip, формы, добавления
и удаления всё ещё требуют проверки на тестовой копии.

## Неустранённые критические пробелы

**Главный стоп-фактор [КОД]:** `OutgoingWholeObjectWriteCoordinator` **не выполняет независимую постзагрузочную проверку содержимого объектов после LOAD перед COMMIT**, хотя старые черновики утверждали обратное. Он сверяет HEAD и Git, затем вызывает COMMIT. Нет сравнения фактически загруженных файлов или контроля `ConfigDumpInfo` вне выбранных владельцев на этой стадии. Нельзя считать механизм безопасным только из-за успешного exit code процесса.

**Другие риски [?]:**
- Корректность полного EDT export → 1С XML round-trip для форм, прав, макетов, реквизитов/типов, переименований и ссылок **не доказана**.
- Root-aware добавления/удаления реализованы после последней успешной CI; риск захвата/воздействия на посторонние верхние объекты и гонки изменения HEAD нужно проверить.
- Не доказана атомарная операция «Commit iff global HEAD==N»; между отдельными проверками и Commit возможна конкурентная версия.
- `OutgoingPublishedGitStore`/Git diff могут выявлять кандидатов, но не гарантируют происхождение изменений после нескольких входящих Merge; список файлов не является однозначным выбором пользователя.
- Нужно проверить, что `/LoadConfigFromFiles ... -listFile` загружает ожидаемую структуру в целевую платформу именно для root XML и конкретных новых/удалённых объектов.
- Проверка `newRevision == oldRevision + 1` сама по себе не доказывает **точный состав** Commit; нужен независимый отчёт помещённых объектов и сравнение изменений.

## Разрешённый порядок испытаний (после отдельного согласия)

1. Собрать **актуальный** HEAD; выполнить unit/OSGi CI (самостоятельно не запускать).
2. Создать **изолированные** копии ИБ **и** хранилища; сверить их исходные ревизии. Не проводить пробные Lock/Load/Commit в реальной ИБ.
3. Сначала испытать существующий простой BSL/форму, затем реквизиты/регистры/права/макеты, затем add/delete, зависимости и конфликтный HEAD. Сверять native Configuration до/после штатными средствами 1С, в том числе вне выбранной области.
4. Проверить негативные сценарии: отказ Lock, отмена после Lock, несовпадение XML, отсутствие файла, изменения чужого объекта, конкурентный Commit, ошибки Load/Commit, журнал UNCERTAIN и запрет автоматического повтора.
5. До исправления постзагрузочной проверки **не заявлять готовность к реальному помещению**, даже при успешной компиляции.

## Основания

[ДОК] Платформа: [операции классического хранилища](https://kb.1ci.com/1C_Enterprise_Platform/Guides/Administrator_Guides/1C_Enterprise_8.3.27_Administrator_Guide/Appendix_7._Startup_command-line_options_of_1C_Enterprise/7.4._Running_Designer_in_batch_mode/7.4.15._Configuration_repository_operations/), [XML/configuration](https://kb.1ci.com/1C_Enterprise_Platform/Guides/Administrator_Guides/1C_Enterprise_8.3.27_Administrator_Guide/Appendix_7._Startup_command-line_options_of_1C_Enterprise/7.4._Running_Designer_in_batch_mode/7.4.4._Configurations_and_extensions/). EDT: [IExportService](https://edt.1c.ru/dev/edt/2025.2/apidocs/com/_1c/g5/v8/dt/export/IExportService.html), [IExportOperationFactory](https://edt.1c.ru/dev/edt/2024.2/apidocs/com/_1c/g5/v8/dt/export/IExportOperationFactory.html). Указанные публикации **не** проверяют автоматически SDK/платформу 2026.2 пользователя.

[КОД] вышеназванные классы по исходникам `4fab919`. [ОПЫТ] известные расхождения round-trip форм в прошлых тестах — повод для отказа, не нормализатор без доказательств. [?] Сквозной безопасный успешный Commit на текущем HEAD.


## 21.10.2026: ServiceUnavailableException при предпросмотре

Локальный `events.log`: `START git=9ead4bd9500246d3aea19eed154c5dcbb62b359a revision=29449`,
`SCOPE owners=3 changed_resources=4 removed_owners=0`,
`FAILED phase=NATIVE_EDT_EXPORT exception=ServiceUnavailableException`.
Ни один из этапов `LOCK / LOAD / COMMIT` не запускался; сбой ещё до
штатного XML-экспорта. В `EdtPublicServiceBridge.previewNativeMetadataExport`
перед созданием `EdtNativeOutgoingExportService` запрашивались четыре OSGi
сервиса через `ServiceAccess.get`; старый журнал не позволял установить
конкретный отсутствующий интерфейс.

Добавлена точечная диагностика `ExportServiceUnavailable`: логирует и
показывает только имя неполученного **типа** службы, например
`IExportOperationFactory`, если проблема именно в нём. Ошибку не заменяют
ручным экспортом, самодельным DI-инжектором или обходом блокировок.
Согласно EDT SDK 2025.2 `IExportOperationFactory` — интерфейс фабрики
(документация не подтверждает его регистрацию в OSGi EDT 2026.2).
Точное имя сервиса и совместимость штатного экспортёра потребуют
повторного диагностического запуска; до этого помещение TEST-ONLY
остаётся заблокировано.

- SDK `IExportOperationFactory`: https://edt.1c.ru/dev/edt/2024.2/apidocs/com/_1c/g5/v8/dt/export/IExportOperationFactory.html
- `ServiceAccess.get`: https://edt.1c.ru/dev/wiring/2.5/apidocs/com/_1c/g5/wiring/ServiceAccess.html


## 22.10.2026: исправление штатной фабрики экспорта [КОД]

Лог испытания: `revision=29456`, `owners=3`,
`changed_resources=3`, `FAILED phase=NATIVE_EDT_EXPORT
exception=SERVICE_UNAVAILABLE service=IExportOperationFactory`.
Перед стадией экспорта Designer `LOCK/LOAD/COMMIT` не запускался.

Ошибка вызвана обращением `ServiceAccess.get(IExportOperationFactory.class)`.
`ServiceAccess` выдаёт зарегистрированные публичные сервисы OSGi, а
наличие `IExportOperationFactory` в API **не** означает такой регистрации.
Исправлен `EdtPublicServiceBridge.previewNativeMetadataExport`:

- `IV8ProjectManager`, `IBmModelManager` и
  `IQualifiedNameFilePathConverter` остаются штатными OSGi-сервисами.
- Бандл, содержащий публичный интерфейс `IExportOperationFactory`,
  определяется через `FrameworkUtil.getBundle`; при необходимости
  активируется без собственного инжектора.
- В том же бандле штатный `ExportPlugin.getDefault().getInjector()`
  используется для получения `IExportOperationFactory`.
  Поскольку `ExportPlugin` расположен во внутреннем пакете, доступ
  к его документированным публичным методам инкапсулирован через
  `Bundle.loadClass` и reflection, **без Import-Package на внутренний
  неэкспортируемый пакет**.
- `BundleException`, ошибки доступа к встроенному инжектору и Guice
  возвращают `ExportServiceUnavailable(IExportOperationFactory)`, а не
  приводят к автоматическому переключению на другой экспортёр.
- `EdtNativeOutgoingExportService` продолжает использовать ту же
  нативную `createExportOperation`/`IExportStrategy.DEFAULT`.
  Оба варианта помещения (ручной и автоматический) используют один путь.

**Никаких собственных Guice-инжекторов, восстановления OSGi-регистрации
в обход EDT или экспериментальных резервных экспортёров не добавлено.**
Пост-LOAD проверка автоматического помещения остаётся незакрытой;
автоматический Commit по-прежнему TEST-ONLY. Компиляция и установленный
EDT 2026.2 runtime этого изменения ещё не подтверждены.

Источники интерфейсов:
- [IExportOperationFactory](https://edt.1c.ru/dev/edt/2024.2/apidocs/com/_1c/g5/v8/dt/export/IExportOperationFactory.html)
- [ExportPlugin.getInjector()](https://edt.1c.ru/dev/edt/2024.2/apidocs/com/_1c/g5/v8/dt/internal/export/ExportPlugin.html)
- [Публичные сервисы EDT](https://edt.1c.ru/dev/ru/docs/plugins/dev/public-services/)


## Исправление 22.10.2026: отмена побайтового отбора при помещении

**[ОПЫТ]** После однострочной правки `Документ.Выплата.ФормаДокумента`
наш старый предварительный список содержал также
`Configuration/Configuration` и `ExchangePlans/МиграцияПриложений`.
Штатное окно EDT показывало **только** правку формы.
Причина — наш старый `OutgoingDeltaScanner`: несходство сериализованных
файлов выдавалось за кандидатов на помещение.

**[КОД]** Файловая сверка и её предварительный список теперь вообще
не вызываются в `OutgoingReadOnlyComparisonCoordinator`. Никаких
`Files.mismatch()`, собственного XML/BSL-diff, Git diff или
запасного списка владельцев для публикации больше нет. Путь:
1. Проект и источник приводятся к двум моделям EDT тем же штатным импортом.
2. Открывается только штатный редактор сравнения EDT **без Merge**.
3. `OutgoingNativeComparisonReview` заранее подписан на
   `IComparisonManager`, собирает данные из штатного
   `IComparisonSession` на `COMPARISON_PROCESS_FINISHED`.
   Чтение дерева делается внутри read-only BM task: только native
   `ComparisonFlags.hasDiffsMainOther()` и native symlink.
4. При закрытии штатного окна имеющийся снимок проверяется;
   `IQualifiedNameFilePathConverter` разрешает native FQN в
   реальный descriptor EDT, затем строится область экспорта.
5. В ручную и автоматическую ветку отправляется **один и тот же**
   семантический список; если native сессия недоступна/снимок
   неполон/есть неподдержанные новые или удалённые объекты —
   **помещение запрещено** (никаких байтовых fallback).
6. Старый `OutgoingDeltaScanner` и его unit-тесты пока оставлены
   как неиспользуемое legacy-ядро, чтобы не ломать старые тесты;
   это **не часть маршрута помещения**.

**[?]** Новый snapshot API и чтение коллекции `ComparisonNode.getChildren()`
не проверены на точном EDT 2026.2.1.3; без сборки/интерактивной проверки
это нельзя считать гарантированно рабочим механизмом.

**Новый лог `events(2).log`:** 29458, git
`2c758a8df814ee94d44325d522069528fe17c0d5`,
`owners=3`, `changed_resources=3`,
`FAILED phase=NATIVE_EDT_EXPORT exception=SERVICE_UNAVAILABLE
service=IExportOperationFactory`. Это ещё **старая трёхобъектная область**,
а не новый native result. Файл не содержит Java stack trace, поэтому
не позволяет установить, почему получение фабрики экспорта всё ещё не
удалось (старая установленная сборка или новая ошибка Guice/OSGi).
Экспорт и помещение не произошли.

**Диагностика:** после этого изменения постоянный краткий `events.log` для `SERVICE_UNAVAILABLE` добавляет `cause=<JavaSimpleClass>` (без текста исключения), а ошибка несоответствия версии показывает `expected=<N> current=<M>`. Это позволяет отличить ошибку OSGi `ServiceAccess` от новой проблемы встроенного экспортного инжектора. Нативные новые/удалённые top owners сознательно блокируются до корректного протокола добавления/удаления. Сборка/EDT 2026.2 проверка отсутствуют.
