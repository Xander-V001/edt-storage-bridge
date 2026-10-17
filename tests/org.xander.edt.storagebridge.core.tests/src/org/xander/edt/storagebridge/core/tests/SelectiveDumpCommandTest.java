package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.SelectiveDumpCommand;
import org.xander.edt.storagebridge.platform.SelectiveDumpRequest;

public class SelectiveDumpCommandTest {
    private static SelectiveDumpRequest request(List<String> names) {
        return new SelectiveDumpRequest(Path.of("/opt/1cv8.exe"),
            new InfobaseLocation(InfobaseLocation.Kind.FILE, "/repo-bound-base"),
            names, Duration.ofMinutes(2));
    }

    @Test
    public void dumpsOnlyExplicitObjectListFromKnownBase() {
        List<String> args = SelectiveDumpCommand.arguments(
            request(List.of("Документ.НаграднойМатериал", "ОбщийМодуль.Тест")),
            Path.of("/private/output"), Path.of("/private/objects.txt"),
            Path.of("/private/log.txt"));
        assertEquals("/F", args.get(2));
        assertEquals("/repo-bound-base", args.get(3));
        int dump = args.indexOf("/DumpConfigToFiles");
        assertTrue(dump > 4);
        assertEquals("-listFile", args.get(dump + 2));
        assertEquals("/private/objects.txt", args.get(dump + 3));
        assertFalse(args.contains("-Format"));
        assertFalse(args.contains("Hierarchical"));
        assertFalse(args.contains("/ConfigurationRepositoryUpdateCfg"));
        assertFalse(args.contains("-force"));
        assertFalse(args.contains("/LoadCfg"));
        assertFalse(args.contains("/UpdateDBCfg"));
    }

    @Test
    public void selectiveDumpUsesInfobaseCredentialsWithoutFullExportFallback() {
        var req = new SelectiveDumpRequest(Path.of("/opt/1cv8.exe"),
            new InfobaseLocation(InfobaseLocation.Kind.FILE, "/repo-bound-base"),
            List.of("Документ.НаграднойМатериал"), Duration.ofMinutes(2),
            "ib-user", "ib-pass");
        var args = SelectiveDumpCommand.arguments(req, Path.of("/tmp/xml"),
            Path.of("/tmp/objects"), Path.of("/tmp/log"));
        assertEquals("ib-user", args.get(args.indexOf("/N") + 1));
        assertEquals("ib-pass", args.get(args.indexOf("/P") + 1));
        assertTrue(args.contains("-listFile"));
        assertFalse(req.toString().contains("ib-pass"));
    }

    @Test
    public void acceptsPlatformRootMetadataObjects() {
        var req = request(List.of("Configuration", "Language"));
        assertEquals(2, req.objectNames().size());
    }

    @Test
    public void fullDumpAndUnsafeNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> request(List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> request(List.of("Документ.Тест", "Документ.Тест")));
        assertThrows(IllegalArgumentException.class,
            () -> request(List.of("Документ.Тест\n/F C:\\\\Base")));
    }
}
