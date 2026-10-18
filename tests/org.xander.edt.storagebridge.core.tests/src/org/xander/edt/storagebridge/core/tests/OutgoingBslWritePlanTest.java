package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.Test;
import org.xander.edt.storagebridge.core.OutgoingBslWritePlan;
import org.xander.edt.storagebridge.core.OutgoingDeltaScanner;

public final class OutgoingBslWritePlanTest {
    @Test
    public void onlyChangedBslModulesAreWriteCandidates() throws Exception {
        Path remote = Files.createTempDirectory("bsl-write-baseline-");
        Path active = Files.createTempDirectory("bsl-write-active-");
        try {
            file(remote,"src/Documents/Order/ManagerModule.bsl","before");
            file(active,"src/Documents/Order/ManagerModule.bsl","after");
            var scope = OutgoingBslWritePlan.fromDelta(
                OutgoingDeltaScanner.compare(remote, active));
            assertEquals(1, scope.classicOwners().size());
            assertTrue(scope.classicOwners().contains("Document.Order"));
            assertEquals("Documents/Order/Ext/ManagerModule.bsl",
                scope.platformFiles().get(0));
            Path output = Files.createTempDirectory("bsl-write-scopes-");
            try {
                scope.writeScopes(output.resolve("objects.xml"),
                    output.resolve("load-list.txt"));
                assertTrue(Files.readString(output.resolve("objects.xml"))
                    .contains("Document.Order"));
                assertTrue(Files.readString(output.resolve("load-list.txt"))
                    .contains("ManagerModule.bsl"));
            } finally { clean(output); }
        } finally { clean(remote); clean(active); }
    }

    @Test
    public void blocksAnyStructuralFormOrDeleteAlongsideBsl() throws Exception {
        Path remote = Files.createTempDirectory("bsl-write-baseline-");
        Path active = Files.createTempDirectory("bsl-write-active-");
        try {
            file(remote,"src/Documents/Order/ManagerModule.bsl","before");
            file(active,"src/Documents/Order/ManagerModule.bsl","after");
            file(active,"src/Documents/Order/Order.mdo","new");
            assertThrows(IOException.class, () -> OutgoingBslWritePlan.fromDelta(
                OutgoingDeltaScanner.compare(remote, active)));
        } finally { clean(remote); clean(active); }
    }

    private static void file(Path root, String relative, String text)
            throws Exception {
        Path f=root.resolve(relative);
        Files.createDirectories(f.getParent());
        Files.writeString(f,text);
    }

    private static void clean(Path root) throws Exception {
        try (var walk=Files.walk(root)) {
            for (var f:walk.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(f);
        }
    }
}
