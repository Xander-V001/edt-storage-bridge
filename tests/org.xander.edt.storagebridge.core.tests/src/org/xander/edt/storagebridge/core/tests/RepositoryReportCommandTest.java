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
import org.xander.edt.storagebridge.platform.RepositoryVersionReportParser;

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
        // Native Designer excludes comments beginning with // unless
        // explicitly requested. Match the unchecked UI exclusion.
        assertTrue(argv.contains("-IncludeCommentLinesWithDoubleSlash"));
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
        assertTrue(args.contains("-IncludeCommentLinesWithDoubleSlash"));
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
        assertTrue(cmd.contains("-IncludeCommentLinesWithDoubleSlash"));
        assertFalse(cmd.contains("-GroupByObject"));
        assertFalse(cmd.contains("-GroupByComment"));
        assertFalse(cmd.contains("-IncludeOnlyVersionsWithLabels"));
        assertFalse(cmd.contains("/ConfigurationRepositoryUpdateCfg"));
        assertFalse(cmd.contains("/ConfigurationRepositoryCommit"));
        assertThrows(UnsupportedOperationException.class,
            () -> cmd.add("/ConfigurationRepositoryCommit"));
    }

    private static final String REAL_REPORT_SHAPE = """
        Отчет по версиям хранилища:\ttcp://example.invalid:1542/Payroll
        Дата отчета:\t17.10.2026
        Время отчета:\t20:32:01

        Версия:\t29403
        Версия конфигурации:\t5.0.113.3
        Пользователь:\tDeveloper One
        Дата создания:\t16.10.2026
        Время создания:\t17:52:38
        Комментарий:
        // БРО 1.2.1.331

        \tДобавлены\t2
        \tДокумент.НовыйДокумент
        \tОбработка.НоваяОбработка.Форма.Основная

        \tИзменены\t2
        \tДокумент.А.Форма.Основная
        \tКаминЗарплата

        \tУдалены\t1
        \tОтчет.Устаревший

        Версия:\t29404
        Версия конфигурации:\t5.0.113.3
        Пользователь:\tDeveloper Two
        Дата создания:\t17.10.2026
        Время создания:\t20:25:45
        Комментарий:
        // тестовые изменения

        \tИзменены\t1
        \tДокумент.НачислениеПоСреднему.Форма.ФормаДокумента
        """;

    @Test
    public void realTxtReportShapeReturnsActualLatestRevisionAndHistory()
            throws Exception {
        var entries = RepositoryVersionReportParser.parse(REAL_REPORT_SHAPE);
        assertEquals(2, entries.size());
        assertEquals(29404L, RepositoryLatestRevisionReader.parseLatest(REAL_REPORT_SHAPE));
        assertEquals(29403L, entries.get(0).number());
        assertEquals("5.0.113.3", entries.get(0).configurationVersion());
        assertEquals("// БРО 1.2.1.331", entries.get(0).comment());
        assertEquals(2, entries.get(0).added().size());
        assertEquals(2, entries.get(0).modified().size());
        assertEquals(1, entries.get(0).deleted().size());
        assertEquals("Developer Two", entries.get(1).username());
        assertEquals("// тестовые изменения", entries.get(1).comment());
        assertEquals("Документ.НачислениеПоСреднему.Форма.ФормаДокумента",
            entries.get(1).modified().get(0));
        assertThrows(UnsupportedOperationException.class,
            () -> entries.get(1).modified().add("Документ.Вставка"));
    }

    @Test
    public void native1cReportWithSingleDigitHourReturns29431() throws Exception {
        // A real October 2026 Designer report used '9:29:53', not '09:29:53'.
        // Use a synthetic endpoint/user to avoid committing personal data.
        String report = """
            Отчет по версиям хранилища:\ttcp://example.invalid:1742/Payroll
            Дата отчета:\t20.10.2026
            Время отчета:\t9:35:05

            Версия:\t29431
            Версия конфигурации:\t5.0.114.1
            Пользователь:\tDeveloper
            Дата создания:\t20.10.2026
            Время создания:\t9:29:53

            \tИзменены\t1
            \tОбщаяФорма.ИнтеграцияССервисомКабинетСотрудника
            """;
        assertEquals(29431L, RepositoryLatestRevisionReader.parseLatest(report));
        var parsedHistory = RepositoryVersionReportParser.parse(report);
        assertEquals(1, parsedHistory.size());
        assertEquals(29431L, parsedHistory.get(0).number());
        assertEquals("", parsedHistory.get(0).comment());
        assertEquals(1, parsedHistory.get(0).modified().size());
        assertEquals(29431L, RepositoryLatestRevisionReader.parseLatest(
            report.replace("Время отчета:\t9:35:05", "Время отчета:\t9:35:20")));
        assertEquals(29431L, RepositoryLatestRevisionReader.parseLatest(
            report.getBytes(java.nio.charset.Charset.forName("windows-1251"))));
        assertEquals(29431L, RepositoryLatestRevisionReader.parseLatest(
            report.replace("Время создания:\t9:29:53",
                "Время создания:\t09:29:53")));
        assertThrows(java.io.IOException.class, () ->
            RepositoryLatestRevisionReader.parseLatest(
                report.replace("Время создания:\t9:29:53",
                    "Время создания:\t25:29:53")));
        assertThrows(java.io.IOException.class, () ->
            RepositoryLatestRevisionReader.parseLatest(
                report.replace("Время создания:\t9:29:53",
                    "Время создания:\t9:99:53")));
        // Missing comment header is valid only when the next content is a
        // native change group. Reject any arbitrary intervening text.
        assertThrows(java.io.IOException.class, () ->
            RepositoryVersionReportParser.parse(report.replace(
                "\tИзменены\t1", "непроверенная строка\n\tИзменены\t1")));
        assertThrows(java.io.IOException.class, () ->
            RepositoryVersionReportParser.parse(report.replace(
                "Время создания:\t9:29:53", "Время создания:\t25:29:53")));
    }

    @Test
    public void nativeBatchReportAcceptsValidatedWindowsAndUnicodeEncodings()
            throws Exception {
        byte[] cp1251 = REAL_REPORT_SHAPE.getBytes(
            java.nio.charset.Charset.forName("windows-1251"));
        assertEquals(29404L,
            RepositoryLatestRevisionReader.parseLatest(cp1251));
        byte[] utf16le = REAL_REPORT_SHAPE.getBytes(
            java.nio.charset.StandardCharsets.UTF_16LE);
        assertEquals(29404L,
            RepositoryLatestRevisionReader.parseLatest(utf16le));
        byte[] utf8 = REAL_REPORT_SHAPE.getBytes(
            java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(29404L,
            RepositoryLatestRevisionReader.parseLatest(utf8));
        byte[] utf8bom = new byte[utf8.length + 3];
        utf8bom[0] = (byte) 0xef;
        utf8bom[1] = (byte) 0xbb;
        utf8bom[2] = (byte) 0xbf;
        System.arraycopy(utf8, 0, utf8bom, 3, utf8.length);
        assertEquals(29404L,
            RepositoryLatestRevisionReader.parseLatest(utf8bom));
        assertThrows(java.io.IOException.class, () ->
            RepositoryLatestRevisionReader.parseLatest(
                "Версия: 9999".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    public void malformedOrSpoofedReportNeverSetsRevision() throws Exception {
        assertThrows(java.io.IOException.class, () ->
            RepositoryLatestRevisionReader.parseLatest(
                "Версия:\t29404\nКомментарий:\n// тестовые изменения"));
        // Latest HEAD extraction must not depend on all change-list counts.
        // The rich history parser still rejects these corrupt details.
        assertEquals(29404L, RepositoryLatestRevisionReader.parseLatest(
            REAL_REPORT_SHAPE.replace("Изменены\t1", "Изменены\t99")));
        assertThrows(java.io.IOException.class, () ->
            RepositoryVersionReportParser.parse(
                REAL_REPORT_SHAPE.replace("Изменены\t1", "Изменены\t99")));
        assertThrows(java.io.IOException.class, () ->
            RepositoryLatestRevisionReader.parseLatest(
                REAL_REPORT_SHAPE.replace("Версия:\t29404", "Версия:\t29403")));
        assertThrows(java.io.IOException.class, () ->
            RepositoryLatestRevisionReader.parseLatest(
                REAL_REPORT_SHAPE.replace("Время создания:\t20:25:45",
                    "Время создания:\tinvalid")));
        // The last-revision report is allowed to omit detailed change lists.
        assertEquals(29404L, RepositoryLatestRevisionReader.parseLatest(
            REAL_REPORT_SHAPE.replace(
                "Документ.НачислениеПоСреднему.Форма.ФормаДокумента", "")));
        assertThrows(java.io.IOException.class, () ->
            RepositoryVersionReportParser.parse(
                REAL_REPORT_SHAPE.replace(
                    "Документ.НачислениеПоСреднему.Форма.ФормаДокумента", "")));
    }

    @Test
    public void nativeLastRevisionDoesNotDependOnCommentsOrChangeSections()
            throws Exception {
        String batch = """
            Отчет по версиям хранилища:\ttcp://example.invalid/repo
            Дата отчета:\t17.10.2026
            Время отчета:\t21:00:00

            Версия:\t29404
            Версия конфигурации:\t5.0.113.3
            Пользователь:\tРазработчик
            Дата создания:\t17.10.2026
            Время создания:\t20:25:45
            Комментарий:
            """;
        assertEquals(29404L, RepositoryLatestRevisionReader.parseLatest(batch));
        assertThrows(java.io.IOException.class, () ->
            RepositoryVersionReportParser.parse(batch));
        assertEquals(29404L, RepositoryLatestRevisionReader.parseLatest(
            batch.replace("Отчет", "Отчёт")));
        assertEquals(29404L, RepositoryLatestRevisionReader.parseLatest(
            batch.replace("Версия:\t29404", "Версия :\t29404")));
        String cp1251Version = batch.replace("Разработчик", "Иванов")
            .replace("29404", "29405");
        assertEquals(29405L, RepositoryLatestRevisionReader.parseLatest(
            cp1251Version.getBytes(java.nio.charset.Charset.forName("windows-1251"))));
    }

    @Test
    public void rejectFakeVersionLinesInsideCommentsWithoutNativeHeaders()
            throws Exception {
        String report = REAL_REPORT_SHAPE.replace(
            "// БРО 1.2.1.331",
            "// БРО 1.2.1.331\nВерсия:\t99999\nПоменяли документ");
        assertEquals(29404L, RepositoryLatestRevisionReader.parseLatest(report));
        assertThrows(java.io.IOException.class, () ->
            RepositoryLatestRevisionReader.parseLatest(
                "Комментарий:\nВерсия:\t99999"));
        assertThrows(java.io.IOException.class, () ->
            RepositoryLatestRevisionReader.parseLatest(
                REAL_REPORT_SHAPE.replace("Версия:\t29404", "Версия:\t29403")));
        assertThrows(java.io.IOException.class, () ->
            RepositoryLatestRevisionReader.parseLatest(
                REAL_REPORT_SHAPE.replace("Время создания:\t20:25:45",
                    "Время создания:\t25:25:45")));
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
