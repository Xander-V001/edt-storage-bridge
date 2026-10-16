package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.RepositoryUpdateCommand;
import org.xander.edt.storagebridge.platform.RepositoryUpdateRequest;

public class RepositoryUpdateCommandTest {

    @Test
    public void fileBaseUpdateUsesOnlyAllowedFlags() {
        var req = new RepositoryUpdateRequest(Path.of("/opt/bin/1cv8.exe"),
            new InfobaseLocation(InfobaseLocation.Kind.FILE, "/test/ib"),
            Duration.ofMinutes(5));
        var args = RepositoryUpdateCommand.arguments(req, Path.of("/temp/out.txt"));
        assertEquals("DESIGNER", args.get(1));
        assertEquals("/F", args.get(2));
        assertEquals("/test/ib", args.get(3));
        assertTrue(args.contains("/ConfigurationRepositoryUpdateCfg"));
        assertTrue(args.contains("-force"));
        assertFalse(args.contains("-revised"));
        assertFalse(args.contains("/ConfigurationRepositoryCommit"));
        assertFalse(args.contains("/P"));
    }

    @Test
    public void serverAddressAndCredentialsAreNotSynthesized() {
        var req = new RepositoryUpdateRequest(Path.of("/opt/bin/1cv8.exe"),
            new InfobaseLocation(InfobaseLocation.Kind.SERVER, "server/ib"),
            Duration.ofMinutes(2));
        var args = RepositoryUpdateCommand.arguments(req, Path.of("/temp/out.txt"));
        assertEquals("/S", args.get(2));
        assertEquals("server/ib", args.get(3));
        assertFalse(args.contains("/N"));
        assertFalse(args.contains("/ConfigurationRepositoryP"));
        assertThrows(UnsupportedOperationException.class, () -> args.add("-revised"));
    }

    @Test
    public void rejectsNonPlatformExeAndRelativePath() {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryUpdateRequest(
            Path.of("/opt/shell.sh"),
            new InfobaseLocation(InfobaseLocation.Kind.FILE, "/test/ib"),
            Duration.ofMinutes(2)));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryUpdateRequest(
            Path.of("1cv8.exe"),
            new InfobaseLocation(InfobaseLocation.Kind.FILE, "/test/ib"),
            Duration.ofMinutes(2)));
    }
}
