package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.nio.file.Path;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.OutgoingDesignerCommands;
import org.xander.edt.storagebridge.platform.InfobaseLocation;

public final class OutgoingDesignerCommandsTest {
    private static OutgoingDesignerCommands.Connection connection() {
        return new OutgoingDesignerCommands.Connection(
            Path.of("C:/1cv8.exe"),
            new InfobaseLocation(InfobaseLocation.Kind.FILE, "C:/test-ib"),
            "testIB", "do-not-log-this-ib-pass",
            "C:/test-repository", "test-repo-user",
            "do-not-log-this-repository-pass");
    }

    @Test
    public void noFullScopeOrForceInNativeCommands() {
        Path xml = Path.of("C:/tmp/objects.xml"),
            load = Path.of("C:/tmp/list.txt"),
            export = Path.of("C:/tmp/export"),
            out = Path.of("C:/tmp/console.txt");
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
        Path path = Path.of("C:/tmp/objects.xml"),
            out = Path.of("C:/tmp/log");
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
