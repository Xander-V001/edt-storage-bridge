package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Comparator;
import java.util.stream.Stream;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.ChangeDetectionRequest;
import org.xander.edt.storagebridge.platform.ChangedObjectsProbe;
import org.xander.edt.storagebridge.platform.ConfigVersionSnapshotRunner;
import org.xander.edt.storagebridge.platform.InfobaseLocation;

/**
 * Validate batch Designer argv without starting 1C or touching a real infobase.
 * 1C's documented one-pass invocation writes both changed XML and -getChanges.
 */
public final class ChangeDetectionCommandTest {
    private static final ChangeDetectionRequest REQUEST =
        new ChangeDetectionRequest(Path.of("/opt/1cv8.exe"),
            new InfobaseLocation(InfobaseLocation.Kind.FILE, "/test/connected-ib"),
            Duration.ofMinutes(2));

    @Test
    public void thinClientCannotRunDesignerSnapshot() {
        assertThrows(IllegalArgumentException.class, () ->
            new ChangeDetectionRequest(Path.of("/opt/1cv8c.exe"),
                REQUEST.infobase(), Duration.ofMinutes(2)));
    }

    @Test
    public void officialIncrementalDumpAndChangeListUseOneDesignerCommand() {
        Path baseline = Path.of("/tmp/previous/ConfigDumpInfo.xml");
        Path root = Path.of("/tmp/inspection");
        Path changes = Path.of("/tmp/changes.txt");
        Path log = Path.of("/tmp/designer.log");
        List<String> argv = new ChangedObjectsProbe().command(
            REQUEST, baseline, root, changes, log);
        assertEquals("DESIGNER", argv.get(1));
        int dump = argv.indexOf("/DumpConfigToFiles");
        assertTrue(dump > 4);
        assertEquals(root.toString(), argv.get(dump + 1));
        assertTrue(argv.contains("-update"));
        assertTrue(argv.contains("-force"));
        assertEquals(baseline.toString(),
            argv.get(argv.indexOf("-configDumpInfoForChanges") + 1));
        assertEquals(changes.toString(),
            argv.get(argv.indexOf("-getChanges") + 1));
        assertTrue(argv.indexOf("-configDumpInfoForChanges")
            < argv.indexOf("-getChanges"));
        assertFalse(argv.contains("-listFile"));
        assertFalse(argv.contains("/ConfigurationRepositoryUpdateCfg"));
    }

    @Test
    public void snapshotAndProbeBothUseSeparateInfobaseLogin() {
        var authenticated = new ChangeDetectionRequest(
            REQUEST.executable(), REQUEST.infobase(), Duration.ofMinutes(2),
            "ib-user", "ib-secret");
        var snapshot = new ConfigVersionSnapshotRunner().command(
            authenticated, Path.of("/tmp/snapshot"), Path.of("/tmp/log"));
        var probe = new ChangedObjectsProbe().command(authenticated,
            Path.of("/tmp/old/ConfigDumpInfo.xml"), Path.of("/tmp/out"),
            Path.of("/tmp/changes"), Path.of("/tmp/log"));
        for (var cmd : List.of(snapshot, probe)) {
            assertEquals("ib-user", cmd.get(cmd.indexOf("/N") + 1));
            assertEquals("ib-secret", cmd.get(cmd.indexOf("/P") + 1));
            assertTrue(cmd.indexOf("/P") < cmd.indexOf("/DumpConfigToFiles"));
        }
        assertFalse(authenticated.toString().contains("ib-secret"));
    }

    @Test
    public void failedSnapshotKeepsExitCodeAndDiagnosticsFolder() {
        var problem = new ConfigVersionSnapshotRunner.SnapshotFailure(
            2, Path.of("/tmp/diagnostic-source"));
        assertEquals(2, problem.exitCode());
        assertEquals(Path.of("/tmp/diagnostic-source").toAbsolutePath(),
            problem.diagnosticsDirectory());
    }

    @Test
    public void failedSnapshotPreservesRealNativeLogsOutsideTemporaryExport()
            throws Exception {
        Path stage = Files.createTempDirectory("1c-snapshot-failure-stage-");
        Path diagnostics = Files.createTempDirectory("1c-snapshot-persist-tests-");
        try {
            Files.writeString(stage.resolve("designer.log"),
                "Platform diagnostic for 1C snapshot failure");
            Files.writeString(stage.resolve("stdout.txt"), "Designer stdout");
            Files.writeString(stage.resolve("result.txt"), "1");
            Path dump = Files.createDirectories(stage.resolve("dump"));
            Files.writeString(dump.resolve("ConfigDumpInfo.xml"),
                "<ConfigDumpInfo/>");
            Path saved = ConfigVersionSnapshotRunner.saveFailureDiagnostics(
                stage, 1, diagnostics);
            assertTrue(Files.isDirectory(saved));
            assertTrue(saved.startsWith(diagnostics));
            assertEquals("Platform diagnostic for 1C snapshot failure",
                Files.readString(saved.resolve("designer.log")));
            assertEquals("Designer stdout",
                Files.readString(saved.resolve("stdout.txt")));
            assertEquals("1",
                Files.readString(saved.resolve("result.txt")));
            assertTrue(Files.readString(saved.resolve("snapshot-failure.txt"))
                .contains("Process exit code: 1"));
            assertFalse(Files.exists(saved.resolve("dump")));
            assertFalse(Files.exists(saved.resolve("ConfigDumpInfo.xml")));
            // The native input is never modified during diagnostic collection.
            assertTrue(Files.exists(dump.resolve("ConfigDumpInfo.xml")));
        } finally {
            erase(stage);
            erase(diagnostics);
        }
    }

    @Test
    public void missingNativeOutStillProducesExistingFailureSummary()
            throws Exception {
        Path stage = Files.createTempDirectory("1c-snapshot-no-stdout-");
        Path diagnostics = Files.createTempDirectory("1c-snapshot-report-");
        try {
            Path saved = ConfigVersionSnapshotRunner.saveFailureDiagnostics(
                stage, 1, diagnostics);
            assertTrue(Files.isDirectory(saved));
            assertTrue(Files.isRegularFile(
                saved.resolve("snapshot-failure.txt")));
            assertFalse(Files.exists(saved.resolve("designer.log")));
        } finally {
            erase(stage);
            erase(diagnostics);
        }
    }

    @Test
    public void nonexistentStagingDoesNotInventDiagnosticDirectory()
            throws Exception {
        Path root = Files.createTempDirectory("1c-snapshot-bad-staging-");
        try {
            assertThrows(IOException.class, () ->
                ConfigVersionSnapshotRunner.saveFailureDiagnostics(
                    root.resolve("missing"), 1, root.resolve("diagnostics")));
            assertFalse(Files.exists(root.resolve("diagnostics")));
        } finally {
            erase(root);
        }
    }

    private static void erase(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    @Test
    public void initialSnapshotExportsOnlyVersionFile() {
        List<String> args = new ConfigVersionSnapshotRunner().command(
            REQUEST, Path.of("/tmp/snapshot"), Path.of("/tmp/snapshot.log"));
        assertTrue(args.contains("-configDumpInfoOnly"));
        assertEquals("/DumpResult", args.get(args.indexOf("/DumpResult")));
        assertEquals(Path.of("/tmp/result.txt").toString(),
            args.get(args.indexOf("/DumpResult") + 1));
        assertFalse(args.contains("-getChanges"));
        assertFalse(args.contains("-update"));
        assertFalse(args.contains("-force"));
        assertFalse(args.contains("/ConfigurationRepositoryUpdateCfg"));
    }
}
