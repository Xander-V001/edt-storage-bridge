package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.Test;
import org.xander.edt.storagebridge.core.OutgoingDeltaScanner;
import org.xander.edt.storagebridge.core.OutgoingDifferenceSummary;

public final class OutgoingDifferenceSummaryTest {
    @Test
    public void reportsAllOwnersWithoutManuallyChoosingBslFiles() throws Exception {
        Path remote = Files.createTempDirectory("outgoing-sum-remote-");
        Path local = Files.createTempDirectory("outgoing-sum-local-");
        try {
            write(remote, "src/Documents/Order/Order.mdo", "before");
            write(local, "src/Documents/Order/Order.mdo", "after");
            write(remote, "src/Documents/Order/ManagerModule.bsl", "same");
            write(local, "src/Documents/Order/ManagerModule.bsl", "same");
            write(local, "src/Catalogs/People/People.mdo", "new object");
            write(remote, "src/Reports/Old/Old.mdo", "old report");
            var summary = OutgoingDifferenceSummary.from(
                OutgoingDeltaScanner.compare(remote, local));
            assertEquals(3, summary.fileCount());
            assertEquals(3, summary.ownerCount());
            assertEquals(1, summary.added());
            assertEquals(1, summary.changed());
            assertEquals(1, summary.removed());
            String text = summary.describe(3);
            assertTrue(text.contains("Documents/Order"));
            assertTrue(text.contains("Catalogs/People"));
            assertTrue(text.contains("Reports/Old"));
            assertTrue(text.contains("отсутствует в EDT"));
        } finally {
            delete(remote);
            delete(local);
        }
    }

    @Test
    public void emptyDiffIsNotAStorageCommit() throws Exception {
        Path remote = Files.createTempDirectory("outgoing-sum-remote-");
        Path local = Files.createTempDirectory("outgoing-sum-local-");
        try {
            write(remote, "src/CommonModules/Calc/Module.bsl", "same");
            write(local, "src/CommonModules/Calc/Module.bsl", "same");
            var summary = OutgoingDifferenceSummary.from(
                OutgoingDeltaScanner.compare(remote, local));
            assertEquals(0, summary.fileCount());
            assertEquals(0, summary.ownerCount());
            assertTrue(summary.describe(16).contains("не обнаружено"));
            assertThrows(IllegalArgumentException.class,
                () -> summary.describe(0));
        } finally {
            delete(remote);
            delete(local);
        }
    }

    private static void write(Path root, String relative, String text)
            throws IOException {
        Path f = root.resolve(relative);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    private static void delete(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
