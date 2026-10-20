package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Set;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.RepositoryRevisionHistoryDelta;

/** Exact-range native report fixtures; never execute a 1C process. */
public class RepositoryRevisionHistoryDeltaTest {
    private static final String VERSIONS = """
        Отчет по версиям хранилища:\ttcp://example.invalid/repo
        Дата отчета:\t20.10.2026
        Время отчета:\t12:00:00

        Версия:\t101
        Версия конфигурации:\t5.0.1
        Пользователь:\tDeveloper
        Дата создания:\t20.10.2026
        Время создания:\t11:00:00
        Комментарий:
        первый

        \tИзменены\t2
        \tДокумент.Заказ
        \tОбработка.Импорт.Форма.Главная

        Версия:\t102
        Версия конфигурации:\t5.0.1
        Пользователь:\tDeveloper
        Дата создания:\t20.10.2026
        Время создания:\t11:05:00

        \tДобавлены\t1
        \tДокумент.Новый

        \tУдалены\t1
        \tСправочник.Старый

        Версия:\t103
        Версия конфигурации:\t5.0.1
        Пользователь:\tDeveloper
        Дата создания:\t20.10.2026
        Время создания:\t11:10:00

        \tИзменены\t1
        \tДокумент.Заказ
        """;

    @Test
    public void nativeHistoryCoversEveryRevision101Through103() throws Exception {
        var candidate = RepositoryRevisionHistoryDelta.fromNativeReport(
            VERSIONS.getBytes(java.nio.charset.StandardCharsets.UTF_8), 100, 103);
        assertEquals(100L, candidate.baseline());
        assertEquals(103L, candidate.head());
        assertEquals(Set.of("Документ.Заказ", "Обработка.Импорт.Форма.Главная",
            "Документ.Новый", "Справочник.Старый"), candidate.touched());

        var nativeChanges = RepositoryRevisionHistoryDelta.forCurrentInventory(
            candidate,
            Set.of("Документ.Заказ", "Справочник.Старый"),
            Set.of("Документ.Заказ", "Документ.Новый",
                "Обработка.Импорт.Форма.Главная"));
        assertFalse(nativeChanges.fullDump());
        assertTrue(nativeChanges.exactNames().contains("Документ.Заказ"));
        assertTrue(nativeChanges.exactNames()
            .contains("Обработка.Импорт.Форма.Главная"));
        assertFalse(nativeChanges.exactNames().contains("Справочник.Старый"));
    }

    @Test
    public void legacyWindows1251NativeReportCanBeDecoded() throws Exception {
        var encoded = VERSIONS.getBytes(Charset.forName("windows-1251"));
        assertEquals(4, RepositoryRevisionHistoryDelta
            .fromNativeReport(encoded, 100, 103).touched().size());
    }

    @Test
    public void missingVersionAndInvalidRangeCannotPass() throws Exception {
        var bytes = VERSIONS.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(IOException.class, () ->
            RepositoryRevisionHistoryDelta.fromNativeReport(bytes, 99, 103));
        assertThrows(IOException.class, () ->
            RepositoryRevisionHistoryDelta.fromNativeReport(bytes, 100, 104));
        assertThrows(IOException.class, () ->
            RepositoryRevisionHistoryDelta.fromNativeReport(
                VERSIONS.replace("Версия:\t102", "Версия:\t104")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8), 100, 103));
        assertThrows(IllegalArgumentException.class, () ->
            RepositoryRevisionHistoryDelta.fromNativeReport(bytes, 103, 100));
    }

    @Test
    public void aTouchedButNowRemovedChildRemainsInSourceScopeConservatively()
            throws Exception {
        var candidates = RepositoryRevisionHistoryDelta.fromNativeReport(
            VERSIONS.getBytes(java.nio.charset.StandardCharsets.UTF_8), 100, 103);
        var changes = RepositoryRevisionHistoryDelta.forCurrentInventory(
            candidates, Set.of("Документ.Заказ"),
            Set.of("Документ.Заказ", "Документ.Новый"));
        assertTrue(changes.exactNames().contains("Обработка.Импорт.Форма.Главная"));
    }
}
