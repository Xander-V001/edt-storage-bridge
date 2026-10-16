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
    public void explicitlyConfiguredRepositoryUsesDocumentedAuthenticationSwitches() {
        var req = new RepositoryUpdateRequest(Path.of("/opt/bin/1cv8.exe"),
            new InfobaseLocation(InfobaseLocation.Kind.FILE, "/test/ib"),
            Duration.ofMinutes(5), "tcp://repo/test", "repository-user",
            "non-loggable-secret");
        var args = RepositoryUpdateCommand.arguments(req, Path.of("/temp/out.txt"));
        int repository = args.indexOf("/ConfigurationRepositoryF");
        int username = args.indexOf("/ConfigurationRepositoryN");
        int password = args.indexOf("/ConfigurationRepositoryP");
        assertTrue(repository > 0);
        assertEquals("tcp://repo/test", args.get(repository + 1));
        assertEquals("repository-user", args.get(username + 1));
        assertEquals("non-loggable-secret", args.get(password + 1));
        assertTrue(args.indexOf("/ConfigurationRepositoryUpdateCfg") > password);
        assertFalse(args.contains("-revised"));
        assertFalse(args.contains("/UpdateDBCfg"));
        assertFalse(req.toString().contains("non-loggable-secret"));
        assertFalse(req.toString().contains("tcp://repo/test"));
    }

    @Test
    public void credentialsCannotBeUsedWithoutRepositoryIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryUpdateRequest(
            Path.of("/opt/bin/1cv8.exe"),
            new InfobaseLocation(InfobaseLocation.Kind.FILE, "/test/ib"),
            Duration.ofMinutes(2), "", "user", "secret"));
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
