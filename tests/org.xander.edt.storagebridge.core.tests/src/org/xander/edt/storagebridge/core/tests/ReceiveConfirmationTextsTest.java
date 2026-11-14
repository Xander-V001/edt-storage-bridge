package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import org.junit.Test;
import org.xander.edt.storagebridge.core.ReceiveConfirmationTexts;

public final class ReceiveConfirmationTextsTest {
    @Test
    public void rendersShortNamesForStorageAndWindowsPaths() {
        assertEquals("Зарплата50", ReceiveConfirmationTexts.displayName(
            "tcp://192.168.180.189:1742/Зарплата50"));
        assertEquals("5.0 хранилище", ReceiveConfirmationTexts.displayName(
            "C:\\1С_базы\\5.0\\Хранилище\\5.0 хранилище"));
        assertEquals("Зарплата50", ReceiveConfirmationTexts.displayName(
            "tcp://host:1742/Зарплата50/"));
    }

    @Test
    public void freshReceiveExplainsEffectWithoutTechnicalJargonOrPaths() {
        var message = ReceiveConfirmationTexts.freshReceive(
            "tcp://host:1742/Зарплата50", "C:\\IB\\5.0 хранилище",
            false, false);
        assertTrue(message.contains("Хранилище: Зарплата50"));
        assertTrue(message.contains("База для получения: 5.0 хранилище"));
        assertTrue(message.contains("Проект EDT не изменяется автоматически"));
        assertTrue(message.contains("могут потеряться"));
        assertFalse(message.contains("Git-ветк"));
        assertFalse(message.contains("tcp://"));
        assertFalse(message.contains("C:\\IB"));
        assertFalse(message.contains("параметры запуска"));
    }

    @Test
    public void fullReviewExplainsFullExport() {
        assertTrue(ReceiveConfirmationTexts.freshReceive(
            "repo", "ib", true, true).contains("будет выгружена вся конфигурация"));
    }

    @Test
    public void distinguishesRepeatedComparisonFromFreshUpdate() {
        String choices = ReceiveConfirmationTexts.pendingChoices(
            "repo", "ib", false);
        assertTrue(choices.contains("БЕЗ обновления базы"));
        assertTrue(choices.contains("Обновить и сравнить"));
        String changed = ReceiveConfirmationTexts.changedIntermediary("repo", "ib");
        assertTrue(changed.contains("старый список объектов небезопасно"));
        assertTrue(changed.contains("Проект EDT не изменится автоматически"));
    }
}
