package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.util.Set;
import org.junit.Test;
import org.xander.edt.storagebridge.core.ReceivePendingExplanation;

public final class ReceivePendingExplanationTest {
    @Test
    public void explainsTwoUnshownRootRecordsRatherThanBlamingEdtCheckboxes() {
        var unresolved = Set.of("Configuration", "Language.Русский");
        String message = ReceivePendingExplanation.format(
            unresolved, unresolved, Set.of());
        assertTrue(message.contains("Configuration — корневой"));
        assertTrue(message.contains("Language.Русский — корневой"));
        assertTrue(message.contains("это НЕ пропущенные галочки EDT"));
        assertTrue(message.contains("полная сверка"));
    }

    @Test
    public void explainsRemovedMetadataWithoutPretendingItWasMerged() {
        String message = ReceivePendingExplanation.format(
            Set.of("Document.Удалённый"), Set.of(),
            Set.of("Document.Удалённый"));
        assertTrue(message.contains("отсутствует в текущей выгрузке 1С"));
        assertTrue(message.contains("Удалённые или переименованные"));
    }

    @Test
    public void noNoiseWhenEverythingReviewed() {
        assertEquals("", ReceivePendingExplanation.format(
            Set.of(), Set.of(), Set.of()));
    }
}
