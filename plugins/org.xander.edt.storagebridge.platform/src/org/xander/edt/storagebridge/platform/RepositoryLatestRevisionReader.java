package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Fail-closed read of the latest numeric classic repository revision.
 *
 * Designer's documented -NBegin -1 asks the native repository for its latest
 * stored revision. Do not infer repository numbers from ConfigDumpInfo
 * configVersion tokens or from a successful repository update process.
 *
 * Read the actual native Russian TXT report fields and validate counted
 * changes per version. Reject unknown formats instead of inventing values.
 */
public final class RepositoryLatestRevisionReader {
    private RepositoryLatestRevisionReader() {}

    public static long parseLatest(String report) throws IOException {
        // Parse all native revision blocks, preserving chronology. In a range
        // report the final parsed revision is the greatest one; when invoked
        // with -NBegin -1 the Designer query itself selects repository HEAD.
        return RepositoryVersionReportParser.latest(report);
    }

    public static long latest(RepositoryHistoryRequest request, String ibUsername,
            String ibPassword, String repositoryAddress, String repositoryUsername,
            String repositoryPassword, BooleanSupplier canceled)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request);
        if (request.beginVersion() != -1 || request.endVersion() != null) {
            throw new IllegalArgumentException("Latest report request required");
        }
        var report = new ReadOnlyReportRunner()
            .readHistory(request, canceled, ibUsername, ibPassword,
                repositoryAddress, repositoryUsername, repositoryPassword);
        try {
            return parseLatest(TextReportDecoder.decode(report.bytes()));
        } catch (CharacterCodingException ex) {
            throw new IOException("Unsupported encoding of 1C repository report", ex);
        }
    }
}
