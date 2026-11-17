package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.*;

public class InitialFullDumpCommandTest {
    @Test
    public void explicitInitialExportHasFullHierarchicalModeAndNoListFile() {
        var request = new InitialFullDumpRequest(Path.of("/opt/1cv8.exe"),
            new InfobaseLocation(InfobaseLocation.Kind.FILE, "/existing-base"),
            Duration.ofHours(2),
            "ib-user", "ib-password");
        List<String> args = InitialFullDumpCommand.arguments(request,
            Path.of("/temp/full-xml"), Path.of("/temp/full-log"));
        assertEquals("/F", args.get(2));
        assertEquals("/existing-base", args.get(3));
        assertTrue(args.contains("/DumpConfigToFiles"));
        assertTrue(args.contains("-Format"));
        assertEquals("Hierarchical", args.get(args.indexOf("-Format") + 1));
        assertFalse(args.contains("-listFile"));
        assertFalse(args.contains("/ConfigurationRepositoryUpdateCfg"));
        assertFalse(args.contains("/UpdateDBCfg"));
        assertFalse(args.contains("-force"));
        assertEquals("ib-user", args.get(args.indexOf("/N") + 1));
        assertEquals("ib-password", args.get(args.indexOf("/P") + 1));
    }
}
