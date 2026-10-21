# Проверки и неустранённые риски (20.10.2026)

Это **не отчёт об успешно пройденных end-to-end тестах**. Разделяет компиляцию, unit/OSGi, установленную EDT и платформенный round-trip. Все пункты ниже требуют явного согласия владельца на запуск CI или использование ИБ.

## Текущие доказательства

- [CI №65](https://github.com/Xander-V001/edt-storage-bridge/actions/runs/39469832794): success для `494ff6b`, но текущий исходящий root-aware код — `931c846` и содержит изменения **после** этого SHA. CI не запускался для окончательного HEAD в доступной истории.
- Были успешные и неуспешные предыдущие CI; наличие success ≠ успешное обновление/помещение в пользовательской EDT 2026.2.
- В прошлых запусках 1С встречались сбои выборочной XML-выгрузки для отдельно обрабатываемых дочерних объектов и различия метаданных форм. Эти симптомы **не** означают допустимость переписывания XML или снижения требований к проверкам.
- Документы, Java unit-тесты, static review и сообщения логов нельзя подменять друг другом.

## Приёмка быстрого получения (чек-лист)

- [ ] На TEST копии хранилища 100→101 изменён **один** BSL: native `-getChanges` указывает точную область; XML импортируется в EDT; native Merge действительно завершён.
- [ ] Локально закоммичен другой объект в Git; его изменения не стираются. Сохранённый локально конфликт отражается в `KEPT_LOCAL` / `PENDING` по решению пользователя; не выдавать весь проект за `APPLIED`.
- [ ] Полные native `ConfigDumpInfo.xml` до/после и `changes.txt` различаются правильно; empty delta не создаёт ложные PENDING. Сценарий 20.10: нулевой `changes.txt`, отсутствующий patch manifest → только подтверждённое отсутствие изменений или ошибка, не «тихий успех».
- [ ] При удалении объекта/подчинённого ресурса корректно открывается EDT compare, отсутствующий владелец не теряется.
- [ ] Соседние дочерние файлы/зависимости дозагружаются **ровно в необходимом составе**, без расширения Merge на чужие изменения; никаких самодельных правок `Configuration.xml`.
- [ ] Неизвестный родитель/тип или нативный `FullDump` не выдаётся за полную успешную выборочную выгрузку.
- [ ] Git ветка/HEAD, HEAD хранилища или ИБ меняется во время операции → запрет ложного подтверждения.
- [ ] Отмена до Update/после Update/в редакторе/после Merge: нет автоматического повторного UPDATE, потери pending, удаления промежуточных файлов пока Merge выполняется.
- [ ] Проверка количеств при полной конфигурации (пример: 29 тысяч): все метаданные НЕ превращаются в 29 тысяч `PENDING` только потому, что импорт глобальный.

## Приёмка полной сверки

- [ ] Явная команда «Полная сверка с хранилищем» выполняет отдельную полную выгрузку, импорт внешней модели, глобальный native ComparisonScope/EDT Merge.
- [ ] Удаление верхнего объекта / новые и вложенные формы / роль / регистр / макет / реквизит видимы в глобальном сравнении.
- [ ] Завершение Merge подтверждается native lifecycle, а не только закрытием окна, и фиксируется `REVIEWED` для достоверного источника.
- [ ] Без полного доказательства равенства `APPLIED` не повышается; локальные решения отображаются отдельно.
- [ ] Разные состояния Git/повтор после сбоя не перезаписывают существующие пользовательские данные.

## Приёмка исходящего помещения — НЕ ЗАПУСКАТЬ НА ПРОДАКШЕНЕ

- [ ] Прежде чем тестировать Commit, **исправить постзагрузочную независимую проверку** в `OutgoingWholeObjectWriteCoordinator`: после LOAD считать фактическое состояние ИБ, сравнить выбранные метаданные с ожидаемыми и подтвердить отсутствие изменений за пределами выбора.
- [ ] Раздельно проверить форматы `lock-objects.xml`, `commit-objects.xml`, `load-list.txt` и реальное применение `Configuration.xml` для добавления/удаления.
- [ ] Проверить наличие и отсутствие `-partial` в фактической командной строке (в исходниках его нет), семантику native загрузки whole-object root через `-listFile`.
- [ ] Проверить BSL, формы, роли, макеты, ссылки/типы реквизитов, добавление/удаление с контролем всех посторонних объектов.
- [ ] Проверить смену HEAD до Lock, после Lock, до Commit; отказ/таймаут на каждом native этапе; неизвестный COMMIT не повторяется; `active.txt` остаётся до ручной сверки.
- [ ] Успешная версия `N+1` подтверждается также отчётом **точного состава** помещённых метаданных, а не только номером.

## Диагностика

- Eclipse **Error Log**: Java исключение и фаза `ReceiveChangesCoordinator` / `OutgoingWholeObjectWriteCoordinator`.
- Локальный `ReceiveBaselineJournal`: snapshot, фаза `ReceiveAttemptLedger`, `PENDING`, `KEPT_LOCAL`, привязка HEAD и Git-ветки; не чистить вручную. Команда «Очистить историю получения…» архивирует состояние после подтверждения, но не восстанавливает неизвестную ИБ.
- Ошибка `NativeEdtImportFailure`: отдельное локальное дерево статусов EDT `IStatus`; в нём могут быть имена метаданных.
- Исходящая операция: `<Eclipse state>/org.xander.edt.storagebridge.ui/outgoing/<scope-hash>/<UUID>/events.log`, `state.txt`, redact-копии Designer; родительский `active.txt` обозначает незавершённый или неизвестный native write.
- Исходники, внутренние пути, учётные данные, native логи и журналы не публиковать на GitHub без проверки и редактирования.

## Что делать при неизвестном результате

Не удалять `active.txt`, не помечать успешным `REVIEWED`/`APPLIED` и не выполнять повторную изменяющую команду вслепую. Проверить реальный HEAD, состав захватов, конфигурацию ИБ-посредника, локальные Git и journals. Испытания только на **отдельной тестовой копии ИБ и хранилища** с разрешения пользователя.


## Native EDT read-only choice observer (эксперимент 21.10.2026)
- [ ] Собрать exact HEAD, проверить API `IComparisonSession`,
  `IBmTask`, `MergeSettings`, OSGi imports в EDT 2026.2.
- [ ] Один/много объектов, вложенная процедура BSL и изменение формы:
  нет повторной формы и нет побайтового сравнения, дерево слушается до Merge.
- [ ] Случаи отмены и неуспешного native Merge оставляют PENDING.
- [ ] После native-FINISHED, проверки Git/HEAD/ИБ REVIEWED поднимается
  с unknown-choice; APPLIED и ложный KEPT_LOCAL не устанавливаются.


### Перехват реальных событий EDT (первая итерация, 21.10.2026)
- [ ] При открытии редактора наблюдатель подписался на `IComparisonSession` (не после закрытия).
- [ ] При переключении флага у объекта появляется `ComparisonTreeNodeEvent.getNodeId()`, строка с FQN, `[x]/[ ]`, `MergeRule`.
- [ ] При повторном переключении того же узла строка меняется на последнее наблюдённое состояние.
- [ ] Изменение вложенной процедуры BSL/элемента формы попадает в список как отдельный ID узла с родительским FQN.
- [ ] При пустых событиях окно честно сообщает, что ничего не получено. Не считать это доказательством успешного Merge.
- [ ] Проверить события `INITIAL/UPDATED/ADDED/REMOVED`, поздние события и отмену Merge.
- [ ] Временное окно **только для просмотра** после быстрого Merge; в нём нет повторного выбора и оно не влияет на REVIEWED/PENDING.


### 2026-10-21 — Native comparison flag filter (diagnostic)
- [ ] With 4 changed owners but many unchanged nested forms/modules, read-only choice dialog contains only nodes whose native EDT `ComparisonFlags.hasDiffsMainOther()` is true; unchanged `DoNotMerge` descendants are absent.
- [ ] A changed unchecked node stays visible as a legitimate candidate for keeping local; never filter solely by unchecked/`DoNotMerge`.
- [ ] An EDT recomputation that removes MAIN/OTHER differences removes the previously observed node from the report (no stale state).
- [ ] One-sided added/deleted nodes still appear if `hasDiffsMainOther()` reports a difference.
- [ ] Summary distinguishes number of delivered tree events, included differing nodes, filtered non-differing nodes, and unread events.
- [ ] No effects on native merge execution, `PENDING`, `REVIEWED`, `KEPT_LOCAL` or `APPLIED`; no byte comparison.


### Причина принудительного полного сравнения EDT (21.10.2026)

- [ ] Если штатный `-getChanges` возвращает `FullDump`, диалог прямо сообщает, что полную выгрузку запросила платформа 1С; `changed=0` при этом не означает отсутствие изменений.
- [ ] Если в `exactNames` есть `Configuration` или `Language`, отображается отдельная причина.
- [ ] При восстановлении попытки или отсутствии объектов из источника отображаются соответствующие причины.
- [ ] Текст не меняет `requiresFull`, ветку выгрузки, `PENDING`, `REVIEWED` или порядок обновления базы-посредника.
- [ ] В `Error Log` записываются только безопасные признаки причин, без имён метаданных и паролей.


### Native observer + historical UNKNOWN after global review (21.10.2026)
- [ ] При недоступной сессии во время старта `sessionNotReadyAttempts`
  растёт, `pendingNodeReads` не увеличивается ложно.
- [ ] Реальная ошибка runComparisonTreeReadonlyTask увеличивает
  `readFailures`; незавершённый in-flight пакет учитывается отдельно.
- [ ] `MERGE_PROCESS_STARTED` с незаконченной read-only задачей
  оставляет диагностику НЕполной, не подтверждая отдельные решения.
- [ ] При успешном FULL Merge, `PENDING=0`, старые selective
  `unknown-choice-*` архивируются по оригинальным ID в
  `full-reviewed-unknown-*`; `REVIEWED` сохраняется, `APPLIED`
  и `KEPT_LOCAL` не меняются.
- [ ] При отмене, неизвестном Merge или непустом `PENDING`
  UNKNOWN не архивируется.
- [ ] Архивные записи не выдаются за принятые или применённые;
  запись архива идёт ДО удаления активных меток.


### UI after global native Merge — no invented statuses
- [ ] Native FULL Merge, all selections on, and PENDING=0: report displays EDT merge completed, 0 pending and verified repository revision, without UNKNOWN archives or diagnostic counters.
- [ ] No automatic conversion of historical unknown choices to accepted, applied or a new archived status; older markers stay unchanged.
- [ ] Selective diagnostic window remains read-only and independent of checkpoint safety.
