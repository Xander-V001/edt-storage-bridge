# Помещение изменений EDT → классическое хранилище 1С — код и ограничения

**Срез реализации:** 21.10.2026, `feature/edt-2026.2-integration`, исходники `4fab919` до документационных изменений. **TEST-ONLY**: здесь описана реально написанная опасная операция. Работу в установленной EDT и на тестовом хранилище не доказали. Никаких запусков в рабочую ИБ.

## Что фактически существует [КОД]

1. `OutgoingReadinessHandler` / `OutgoingReadinessCoordinator`: проверка Git, `ReceiveRevisionStore.reviewed`, `ReceivePendingStore`, текущего HEAD хранилища; при несовпадении предлагает пройти получение. Проверка — **не** автоматическое разрешение на помещение.
2. `OutgoingReadOnlyComparisonCoordinator`: сравнение существующего проекта EDT с полученной из текущего посредника временной EDT-моделью в **неизменяющем** режиме. `OutgoingDeltaScanner` группирует отличия как кандидатов; `OutgoingMetadataObjectPlan` сопоставляет их с верхними владельцами, включая вложенные ресурсы, новые и удалённые метаданные.
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

**Связь со следующим получением:** `ReceiveChangesCoordinator` также не возвращает `KEPT_LOCAL` в новый `-getChanges` scope. Если старое изменение оставили локально и в хранилище объект больше не меняли, быстрый приём не обязан предложить его снова. Полная сверка делает глобальный native EDT compare с текущим хранилищем. Сохранённая отметка не управляет этим выбором автоматически.

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
