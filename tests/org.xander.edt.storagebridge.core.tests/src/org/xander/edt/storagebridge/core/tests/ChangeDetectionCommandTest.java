package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.ChangeDetectionRequest;
import org.xander.edt.storagebridge.platform.ChangedObjectsProbe;
import org.xander.edt.storagebridge.platform.ConfigVersionSnapshotRunner;
import org.xander.edt.storagebridge.platform.InfobaseLocation;

/**
 * Validate batch Designer argv without starting 1C or touching a real infobase.
 * 1C requires -configDumpInfoForChanges with both -update and -getChanges.
 */
public final class ChangeDetectionCommandTest {
    private static final ChangeDetectionRequest REQUEST =
        new ChangeDetectionRequest(Path.of("/opt/1cv8.exe"),
            new InfobaseLocation(InfobaseLocation.Kind.FILE, "/test/connected-ib"),
            Duration.ofMinutes(2));

    @Test
    public void incrementalChangeDetectionUsesRequiredUpdateSwitch() {
        Path baseline = Path.of("/tmp/previous/ConfigDumpInfo.xml");
        Path root = Path.of("/tmp/inspection");
        Path changes = Path.of("/tmp/changes.txt");
        Path log = Path.of("/tmp/designer.log");
        List<String> args = new ChangedObjectsProbe().command(
            REQUEST, baseline, root, changes, log);
        assertEquals("DESIGNER", args.get(1));
        int dump = args.indexOf("/DumpConfigToFiles");
        assertTrue(dump > 4);
        assertEquals(root.toString(), args.get(dump + 1));
        assertEquals("-update", args.get(dump + 2));
        assertEquals("-getChanges", args.get(dump + 3));
        assertEquals(changes.toString(), args.get(dump + 4));
        assertEquals("-configDumpInfoForChanges", args.get(dump + 5));
        assertEquals(baseline.toString(), args.get(dump + 6));
        assertFalse(args.contains("-force"));
        assertFalse(args.contains("-listFile"));
        assertFalse(args.contains("/ConfigurationRepositoryUpdateCfg"));
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
    public void initialSnapshotExportsOnlyVersionFile() {
        List<String> args = new ConfigVersionSnapshotRunner().command(
            REQUEST, Path.of("/tmp/snapshot"), Path.of("/tmp/snapshot.log"));
        assertTrue(args.contains("-configDumpInfoOnly"));
        assertFalse(args.contains("-getChanges"));
        assertFalse(args.contains("-update"));
        assertFalse(args.contains("-force"));
        assertFalse(args.contains("/ConfigurationRepositoryUpdateCfg"));
    }
}
