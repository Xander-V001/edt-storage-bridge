package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.ConfigDumpInfoIndex;
import org.xander.edt.storagebridge.platform.OutgoingSelectedVersionCheck;

public final class OutgoingSelectedVersionCheckTest {
    private static ConfigDumpInfoIndex.Version version(String id, String token) {
        return new ConfigDumpInfoIndex.Version(id, token);
    }

    @Test
    public void unrelatedRemoteObjectNeverBlocksSelectedModule() {
        var original = Map.of(
            "Document.Order.ManagerModule", version("1", "a"),
            "Document.Other.Form.Main", version("2", "a"));
        var current = Map.of(
            "Document.Order.ManagerModule", version("1", "a"),
            "Document.Other.Form.Main", version("2", "b"));
        var result = OutgoingSelectedVersionCheck.compare(
            original, current, Set.of("Document.Order.ManagerModule"));
        assertTrue(result.hasNoDetectedRemoteChange());
        assertEquals(OutgoingSelectedVersionCheck.State.UNCHANGED,
            result.selected().get("Document.Order.ManagerModule"));
    }

    @Test
    public void changedSelectedFormRequiresObjectLevelConflictReview() {
        var result = OutgoingSelectedVersionCheck.compare(
            Map.of("Document.Order.Form.Main", version("1", "old")),
            Map.of("Document.Order.Form.Main", version("1", "new")),
            Set.of("Document.Order.Form.Main"));
        assertFalse(result.hasNoDetectedRemoteChange());
        assertEquals(OutgoingSelectedVersionCheck.State.REMOTE_CHANGED,
            result.selected().get("Document.Order.Form.Main"));
    }

    @Test
    public void nameReuseWithDifferentNativeIdIsNotSafe() {
        var result = OutgoingSelectedVersionCheck.compare(
            Map.of("Catalog.Goods.ManagerModule", version("original", "x")),
            Map.of("Catalog.Goods.ManagerModule", version("replacement", "x")),
            Set.of("Catalog.Goods.ManagerModule"));
        assertEquals(OutgoingSelectedVersionCheck.State.REPLACED,
            result.selected().get("Catalog.Goods.ManagerModule"));
    }

    @Test
    public void missingBaselineOrDeletedRemoteMustBeReviewed() {
        var result = OutgoingSelectedVersionCheck.compare(
            Map.of("CommonModule.Tools", version("1", "old")),
            Map.of(),
            Set.of("CommonModule.Tools", "CommonModule.New"));
        assertEquals(OutgoingSelectedVersionCheck.State.REMOVED,
            result.selected().get("CommonModule.Tools"));
        assertEquals(OutgoingSelectedVersionCheck.State.NO_BASELINE,
            result.selected().get("CommonModule.New"));
        assertFalse(result.hasNoDetectedRemoteChange());
    }

    @Test
    public void rejectEmptyOrMalformedSelections() {
        assertThrows(IllegalArgumentException.class,
            () -> OutgoingSelectedVersionCheck.compare(
                Map.of(), Map.of(), Set.of()));
        assertThrows(IllegalArgumentException.class,
            () -> OutgoingSelectedVersionCheck.compare(
                Map.of(), Map.of(), Set.of("Document.Order/../../bad")));
    }
}
