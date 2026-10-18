package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.nio.file.Path;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.OutgoingDesignerCommands;
import org.xander.edt.storagebridge.platform.InfobaseLocation;

public final class OutgoingDesignerCommandsTest {
    /** Cross-platform absolute paths: Tycho tests run on Linux too. */
    private static Path absolute(String file) {
        return Path.of(System.getProperty("java.io.tmpdir"),
            "edt-storage-native-command-test", file).toAbsolutePath();
    }

    private static OutgoingDesignerCommands.Connection connection() {
        return new OutgoingDesignerCommands.Connection(
            absolute("1cv8.exe"),
            new InfobaseLocation(InfobaseLocation.Kind.FILE, "C:/test-ib"),
            "testIB", "do-not-log-this-ib-pass",
            "C:/test-repository", "test-repo-user",
            "do-not-log-this-repository-pass");
    }

    @Test
    public void noFullScopeOrForceInNativeCommands() {
        Path xml = absolute("objects.xml"),
            load = absolute("list.txt"),
            export = absolute("export"),
            out = absolute("console.txt");
        for (var stage : OutgoingDesignerCommands.Stage.values()) {
            var args = OutgoingDesignerCommands.arguments(
                connection(), stage, xml, export, load, out, "Test changes");
            assertTrue(args.contains("/Out"));
            assertFalse(args.contains("-force"));
            assertFalse(args.contains("-NoCheck"));
            assertFalse(args.contains("-revised"));
            if (stage == OutgoingDesignerCommands.Stage.LOAD) {
                assertTrue(args.contains("-partial"));
                assertTrue(args.contains("-listFile"));
                assertFalse(args.contains("-Objects"));
                assertTrue(args.contains("Hierarchical"));
            } else {
                assertTrue(args.contains("-Objects"));
                assertFalse(args.contains("-listFile"));
            }
            assertFalse(args.toString().contains("secret-in-log"));
        }
        assertFalse(connection().toString().contains("do-not-log"));
    }

    @Test
    public void refusesMissingScopesAndMissingComment() {
        Path path = absolute("objects.xml"),
            out = absolute("log");
        assertThrows(IllegalArgumentException.class, () ->
            OutgoingDesignerCommands.arguments(connection(),
                OutgoingDesignerCommands.Stage.LOCK, null, null, null, out, null));
        assertThrows(IllegalArgumentException.class, () ->
            OutgoingDesignerCommands.arguments(connection(),
                OutgoingDesignerCommands.Stage.LOAD, path, null, null, out, null));
        assertThrows(IllegalArgumentException.class, () ->
            OutgoingDesignerCommands.arguments(connection(),
                OutgoingDesignerCommands.Stage.COMMIT, path, null, null, out, ""));
    }
}
