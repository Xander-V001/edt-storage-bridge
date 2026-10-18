package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.xander.edt.storagebridge.core.OutgoingNativeVersionVerifier;
import org.xander.edt.storagebridge.platform.ConfigDumpInfoIndex;

public final class OutgoingNativeVersionVerifierTest {
    private static ConfigDumpInfoIndex.Version token(String value) {
        return new ConfigDumpInfoIndex.Version("same-uuid", value);
    }

    @Test
    public void onlySelectedModulesMayChange() throws Exception {
        var before = Map.of("Document.Order.ManagerModule", token("1"),
            "Catalog.Users", token("unchanged"));
        var after = Map.of("Document.Order.ManagerModule", token("2"),
            "Catalog.Users", token("unchanged"));
        assertEquals(Set.of("Document.Order.ManagerModule"),
            OutgoingNativeVersionVerifier.changedOnlyWithinOwners(
                before, after, Set.of("Document.Order")));
    }

    @Test
    public void unrelatedModificationBlocksNativeCommit() {
        var before = Map.of("Document.Order.ManagerModule", token("1"),
            "Catalog.Users", token("1"));
        var after = Map.of("Document.Order.ManagerModule", token("2"),
            "Catalog.Users", token("2"));
        assertThrows(IOException.class, () ->
            OutgoingNativeVersionVerifier.changedOnlyWithinOwners(
                before, after, Set.of("Document.Order")));
        assertThrows(IOException.class, () ->
            OutgoingNativeVersionVerifier.changedOnlyWithinOwners(
                before, before, Set.of("Document.Order")));
    }
}
