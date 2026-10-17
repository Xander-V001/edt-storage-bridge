package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.ReadOnlyReportCommand;
import org.xander.edt.storagebridge.platform.ReadOnlyReportRunner;
import org.xander.edt.storagebridge.platform.RepositoryHistoryReport;
import org.xander.edt.storagebridge.platform.RepositoryHistoryRequest;
import org.xander.edt.storagebridge.platform.RepositoryLatestRevisionReader;

/** Unit checks: NEVER execute or connect to the 1C platform. */
public class RepositoryReportCommandTest {

    private static final Path EXE = Path.of("/opt/1cv8/bin/1cv8.exe");
    private static final Path REPORT = Path.of("/tmp/edt-storage-report/history.txt");
    private static final Path LOG = Path.of("/tmp/edt-storage-report/designer.txt");

    @Test
    public void latestReportHasOnlyReadOnlyCommandAndTextFormat() {
        var request = RepositoryHistoryRequest.latest(EXE,
            new InfobaseLocation(InfobaseLocation.Kind.FILE, "/workspace/ib"),
            Duration.ofMinutes(5));
        List<String> argv = ReadOnlyReportCommand.arguments(request, REPORT, LOG);
        assertEquals("DESIGNER", argv.get(1));
        assertEquals("/F", argv.get(2));
        assertEquals("/workspace/ib", argv.get(3));
        assertTrue(argv.contains("/DisableStartupDialogs"));
        assertTrue(argv.contains("/ConfigurationRepositoryReport"));
        assertTrue(argv.contains("-ReportFormat"));
        assertTrue(argv.contains("txt"));
        assertEquals("-1", argv.get(argv.indexOf("-NBegin") + 1));
        assertFalse(argv.contains("/ConfigurationRepositoryUpdateCfg"));
        assertFalse(argv.contains("/ConfigurationRepositoryCommit"));
        assertFalse(argv.contains("/ConfigurationRepositoryBindCfg"));
        assertFalse(argv.contains("/ConfigurationRepositoryP"));
        assertFalse(argv.contains("/P"));
    }

    @Test
    public void rangedServerReportUsesCorrectVersionBounds() {
        var request = RepositoryHistoryRequest.range(EXE,
            new InfobaseLocation(InfobaseLocation.Kind.SERVER, "srv/ib"),
            29397, 29410, Duration.ofSeconds(30));
        var args = ReadOnlyReportCommand.arguments(request, REPORT, LOG);
        assertEquals("/S", args.get(2));
        assertEquals("29397", args.get(args.indexOf("-NBegin") + 1));
        assertEquals("29410", args.get(args.indexOf("-NEnd") + 1));
        assertEquals(REPORT.toString(),
            args.get(args.indexOf("/ConfigurationRepositoryReport") + 1));
    }

    @Test
    public void rejectInvalidHistoryRangeAndMissingBase() {
        var location = new InfobaseLocation(InfobaseLocation.Kind.FILE, "/db");
        assertThrows(IllegalArgumentException.class, () ->
            RepositoryHistoryRequest.range(EXE, location, 42, 41, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () ->
            RepositoryHistoryRequest.range(EXE, location, 0, 42, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () ->
            RepositoryHistoryRequest.latest(EXE, location, Duration.ofHours(2)));
        assertThrows(IllegalArgumentException.class, () ->
            new InfobaseLocation(InfobaseLocation.Kind.FILE, "   "));
    }

    @Test
    public void rawReportDefensivelyCopiesBytes() {
        byte[] mutable = new byte[] { 1, 2, 3 };
        RepositoryHistoryReport report = new RepositoryHistoryReport(mutable, -1, null);
        mutable[0] = 9;
        assertEquals(1, report.bytes()[0]);
        byte[] anotherCopy = report.bytes();
        anotherCopy[1] = 0;
        assertEquals(2, report.bytes()[1]);
        assertEquals(3, report.byteCount());
        assertThrows(IllegalArgumentException.class, () ->
            new RepositoryHistoryReport(new byte[0], -1, null));
    }

    @Test
    public void latestReportTargetsSameRepositoryAsDesignerUpdate() {
        var request = RepositoryHistoryRequest.latest(EXE,
            new InfobaseLocation(InfobaseLocation.Kind.FILE, "/workspace/ib"),
            Duration.ofMinutes(5));
        var cmd = ReadOnlyReportRunner.commandArguments(request, REPORT, LOG,
            "ib-admin", "ib-password",
            "tcp://example.invalid:1542/Payroll", "storage-user", "storage-secret");
        assertEquals("/F", cmd.get(2));
        assertEquals("ib-admin", cmd.get(cmd.indexOf("/N") + 1));
        assertEquals("ib-password", cmd.get(cmd.indexOf("/P") + 1));
        assertEquals("tcp://example.invalid:1542/Payroll",
            cmd.get(cmd.indexOf("/ConfigurationRepositoryF") + 1));
        assertEquals("storage-user",
            cmd.get(cmd.indexOf("/ConfigurationRepositoryN") + 1));
        assertEquals("storage-secret",
            cmd.get(cmd.indexOf("/ConfigurationRepositoryP") + 1));
        assertEquals("-1", cmd.get(cmd.indexOf("-NBegin") + 1));
        assertFalse(cmd.contains("/ConfigurationRepositoryUpdateCfg"));
        assertFalse(cmd.contains("/ConfigurationRepositoryCommit"));
        assertThrows(UnsupportedOperationException.class,
            () -> cmd.add("/ConfigurationRepositoryCommit"));
    }

    @Test
    public void latestRevisionMustComeFromSingleNativeVersionHeading()
            throws Exception {
        assertEquals(29397L, RepositoryLatestRevisionReader.parseLatest(
            "Отчёт по истории хранилища\r\nВерсия: 29397\r\nДата: 17.10.2026\r\n"));
        assertEquals(101L, RepositoryLatestRevisionReader.parseLatest(
            "Repository history\nVersion:\t101\n"));
        assertThrows(java.io.IOException.class, () ->
            RepositoryLatestRevisionReader.parseLatest(
                "Configuration version 8.3.27\nLast processed object: 101\n"));
        assertThrows(java.io.IOException.class, () ->
            RepositoryLatestRevisionReader.parseLatest(
                "Версия: 101\nКомментарий:\nВерсия: 999\n"));
        assertThrows(java.io.IOException.class, () ->
            RepositoryLatestRevisionReader.parseLatest(
                "Версия: 0\n"));
    }

    @Test
    public void commandCannotBeMutatedByCaller() {
        var request = RepositoryHistoryRequest.latest(EXE,
            new InfobaseLocation(InfobaseLocation.Kind.FILE, "/db"),
            Duration.ofMinutes(2));
        var args = ReadOnlyReportCommand.arguments(request, REPORT, LOG);
        assertThrows(UnsupportedOperationException.class, () -> args.add("/UpdateDBCfg"));
    }
}
