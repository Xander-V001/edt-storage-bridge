package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.NativeRepositoryCompare;

/** Build commands without launching Designer or accessing a real storage. */
public class NativeRepositoryCompareTest {
    private static final Path EXE = Path.of("/opt/1cv8/bin/1cv8.exe");
    private static final Path TXT = Path.of("/tmp/native-compare/report.txt");
    private static final Path OUT = Path.of("/tmp/native-compare/designer.txt");
    private static final InfobaseLocation IB =
        new InfobaseLocation(InfobaseLocation.Kind.FILE, "/workspace/ib");

    @Test
    public void compareVersions100And103UsingOnlyReadOnlyNativeDesigner() {
        List<String> cmd = NativeRepositoryCompare.command(
            EXE, IB, "ib-user", "ib-secret", "/repo/storage",
            "repo-user", "repo-secret", 100, 103, TXT, OUT);
        assertEquals("DESIGNER", cmd.get(1));
        assertEquals("/F", cmd.get(2));
        assertEquals("/workspace/ib", cmd.get(3));
        assertEquals("100", cmd.get(cmd.indexOf("-FirstVersion") + 1));
        assertEquals("103", cmd.get(cmd.indexOf("-SecondVersion") + 1));
        assertEquals("ConfigurationRepository",
            cmd.get(cmd.indexOf("-FirstConfigurationType") + 1));
        assertEquals("ConfigurationRepository",
            cmd.get(cmd.indexOf("-SecondConfigurationType") + 1));
        assertEquals("Brief", cmd.get(cmd.indexOf("-ReportType") + 1));
        assertEquals("txt", cmd.get(cmd.indexOf("-ReportFormat") + 1));
        assertEquals(TXT.toString(), cmd.get(cmd.indexOf("-ReportFile") + 1));
        assertEquals("ib-user", cmd.get(cmd.indexOf("/N") + 1));
        assertEquals("ib-secret", cmd.get(cmd.indexOf("/P") + 1));
        assertEquals("/repo/storage",
            cmd.get(cmd.indexOf("/ConfigurationRepositoryF") + 1));
        assertEquals("repo-user",
            cmd.get(cmd.indexOf("/ConfigurationRepositoryN") + 1));
        assertEquals("repo-secret",
            cmd.get(cmd.indexOf("/ConfigurationRepositoryP") + 1));
        assertTrue(cmd.contains("-IncludeChangedObjects"));
        assertTrue(cmd.contains("-IncludeAddedObjects"));
        assertTrue(cmd.contains("-IncludeDeletedObjects"));
        assertFalse(cmd.contains("/ConfigurationRepositoryUpdateCfg"));
        assertFalse(cmd.contains("/ConfigurationRepositoryCommit"));
        assertFalse(cmd.contains("/ConfigurationRepositoryLock"));
        assertFalse(cmd.contains("/LoadConfigFromFiles"));
        assertThrows(UnsupportedOperationException.class,
            () -> cmd.add("/ConfigurationRepositoryCommit"));
    }

    @Test
    public void invalidOrReversedRevisionsAreRejectedBeforeAnyProcess() {
        assertThrows(IllegalArgumentException.class, () ->
            NativeRepositoryCompare.command(EXE, IB, "", "", "/repo",
                "", "", 103, 100, TXT, OUT));
        assertThrows(IllegalArgumentException.class, () ->
            NativeRepositoryCompare.command(EXE, IB, "", "", "/repo",
                "", "", 100, 100, TXT, OUT));
        assertThrows(IllegalArgumentException.class, () ->
            NativeRepositoryCompare.command(EXE, IB, "", "", "/repo",
                "", "", 0, 103, TXT, OUT));
        assertThrows(IllegalArgumentException.class, () ->
            NativeRepositoryCompare.command(EXE, IB, "", "", "/repo",
                "", "secret", 100, 103, TXT, OUT));
        assertThrows(IllegalArgumentException.class, () ->
            NativeRepositoryCompare.command(EXE, IB, "", "", "",
                "", "", 100, 103, TXT, OUT));
        assertThrows(IllegalArgumentException.class, () ->
            NativeRepositoryCompare.command(EXE, IB, "", "", "/repo\n/Commit",
                "", "", 100, 103, TXT, OUT));
        assertThrows(IllegalArgumentException.class, () ->
            new NativeRepositoryCompare.Result(103, 100, 1));
        assertThrows(IllegalArgumentException.class, () ->
            new NativeRepositoryCompare.Result(100, 103, 0));
    }

    @Test
    public void comparisonUsesOnlyExplicitRevisionAndNoCredentialsInResult() {
        var result = new NativeRepositoryCompare.Result(100, 103, 245);
        assertEquals(100, result.fromRevision());
        assertEquals(103, result.toRevision());
        assertEquals(245, result.reportBytes());
        var noAuth = NativeRepositoryCompare.command(
            EXE, IB, "", "", "/repo", "", "", 100, 103, TXT, OUT);
        assertFalse(noAuth.contains("/P"));
        assertFalse(noAuth.contains("/ConfigurationRepositoryP"));
    }
}
