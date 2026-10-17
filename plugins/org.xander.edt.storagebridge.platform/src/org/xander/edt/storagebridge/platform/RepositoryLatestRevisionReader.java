package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Fail-closed read of the latest numeric classic repository revision.
 *
 * Designer's documented -NBegin -1 asks the native repository for its latest
 * stored revision. Do not infer repository numbers from ConfigDumpInfo
 * configVersion tokens or from a successful repository update process.
 *
 * TXT report layouts depend on platform and compatibility mode. Only a
 * single unambiguous standalone "Версия: N" / "Version: N" heading is
 * accepted. An unknown report leaves the version UNKNOWN (never invented).
 */
public final class RepositoryLatestRevisionReader {
    private static final Pattern HEADING = Pattern.compile(
        "(?iu)^[ \\t]*(?:Версия|Version)[ \\t]*:[ \\t]*([1-9][0-9]{0,15})[ \\t]*$",
        Pattern.MULTILINE);

    private RepositoryLatestRevisionReader() {}

    public static long parseLatest(String report) throws IOException {
        Objects.requireNonNull(report, "report");
        var matcher = HEADING.matcher(report);
        AtomicInteger count = new AtomicInteger();
        long latest = -1;
        while (matcher.find()) {
            count.incrementAndGet();
            try {
                latest = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ex) {
                throw new IOException("Invalid repository report revision", ex);
            }
        }
        if (count.get() != 1 || latest < 1) {
            throw new IOException(
                "Cannot unambiguously identify latest revision in native 1C report");
        }
        return latest;
    }

    public static long latest(RepositoryHistoryRequest request, String ibUsername,
            String ibPassword, BooleanSupplier canceled)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request);
        if (request.beginVersion() != -1 || request.endVersion() != null) {
            throw new IllegalArgumentException("Latest report request required");
        }
        var report = new ReadOnlyReportRunner()
            .readHistory(request, canceled, ibUsername, ibPassword);
        try {
            return parseLatest(TextReportDecoder.decode(report.bytes()));
        } catch (CharacterCodingException ex) {
            throw new IOException("Unsupported encoding of 1C repository report", ex);
        }
    }
}
