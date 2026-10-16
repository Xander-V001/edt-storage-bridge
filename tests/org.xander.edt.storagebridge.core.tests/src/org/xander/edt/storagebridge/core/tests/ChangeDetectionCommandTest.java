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
        assertEquals("/DumpConfigToFiles", args.get(5));
        assertEquals(root.toString(), args.get(6));
        assertEquals("-update", args.get(7));
        assertEquals("-getChanges", args.get(8));
        assertEquals(changes.toString(), args.get(9));
        assertEquals("-configDumpInfoForChanges", args.get(10));
        assertEquals(baseline.toString(), args.get(11));
        assertFalse(args.contains("-force"));
        assertFalse(args.contains("-listFile"));
        assertFalse(args.contains("/ConfigurationRepositoryUpdateCfg"));
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
