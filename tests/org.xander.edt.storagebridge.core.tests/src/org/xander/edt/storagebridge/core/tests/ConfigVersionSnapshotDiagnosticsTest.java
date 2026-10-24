package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.ConfigVersionSnapshotRunner;

/** Deterministic offline diagnostics test; never starts Designer or touches IB. */
public final class ConfigVersionSnapshotDiagnosticsTest {
    @Test
    public void privateNativeReportsAreRetainedWithoutAnyConfigurationXml()
            throws Exception {
        Path root = Files.createTempDirectory("edt-native-diagnostics-test-");
        try {
            Path stage = Files.createDirectory(root.resolve("stage"));
            Path archive = Files.createDirectory(root.resolve("private"));
            Files.writeString(stage.resolve("designer.log"), "native reason");
            Files.writeString(stage.resolve("stdout.txt"), "console out");
            Files.writeString(stage.resolve("stderr.txt"), "console error");
            Path xmlDir = Files.createDirectory(stage.resolve("dump"));
            Files.writeString(xmlDir.resolve("ConfigDumpInfo.xml"),
                "<ConfigDumpInfo/>");

            Path saved = ConfigVersionSnapshotRunner.saveFailureDiagnostics(
                stage, -1, archive);
            assertTrue(Files.isRegularFile(saved.resolve("designer.log")));
            assertTrue(Files.isRegularFile(saved.resolve("stdout.txt")));
            assertTrue(Files.isRegularFile(saved.resolve("stderr.txt")));
            assertEquals("console error", Files.readString(saved.resolve("stderr.txt")));
            assertTrue(Files.readString(saved.resolve("snapshot-failure.txt"))
                .contains("Process exit code: -1"));
            assertFalse(Files.exists(saved.resolve("dump")));
            assertFalse(Files.exists(saved.resolve("ConfigDumpInfo.xml")));
        } finally {
            deleteTree(root);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var entries = Files.walk(root)) {
            for (Path item : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
