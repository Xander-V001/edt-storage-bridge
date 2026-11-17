package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.util.Set;
import org.junit.Test;
import org.xander.edt.storagebridge.core.ReceiveReviewScope;

public final class ReceiveReviewScopeTest {
    @Test
    public void selectiveReviewLeavesDeferredConfigurationAndLanguagePending() {
        Set<String> all = Set.of("Document.Заказ",
            "Configuration", "Language.Русский");
        assertEquals(Set.of("Document.Заказ"),
            ReceiveReviewScope.eligible(all, all,
                Set.of("Configuration", "Language.Русский"), false));
    }

    @Test
    public void explicitlyPromotedFullReviewCanIncludeThoseTwoEntries() {
        Set<String> all = Set.of("Document.Заказ",
            "Configuration", "Language.Русский");
        assertEquals(all, ReceiveReviewScope.eligible(all, all,
            Set.of("Configuration", "Language.Русский"), true));
    }

    @Test
    public void deletedMetadataCannotBeMarkedReviewedJustByCheckboxes() {
        Set<String> pending = Set.of("Document.Существующий",
            "Document.Удалённый");
        Set<String> inSource = Set.of("Document.Существующий");
        assertEquals(Set.of("Document.Существующий"),
            ReceiveReviewScope.eligible(pending, inSource, Set.of(), true));
        assertEquals(Set.of("Document.Удалённый"),
            ReceiveReviewScope.missingFromSource(pending, inSource));
    }

    @Test
    public void emptyPendingNeverInventsMetadata() {
        assertTrue(ReceiveReviewScope.eligible(
            Set.of(), Set.of("Configuration"), Set.of(), true).isEmpty());
    }
}
