package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
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

    /**
     * Batch Designer report encodings can differ from GUI-saved TXT exports.
     * Prefer the BOM-aware strict decoder. For BOM-less reports only, try
     * UTF-8, UTF-16LE and Windows-1251. A candidate is trusted ONLY if the
     * complete native report parser validates its version blocks, timestamps
     * and declared object counts. Invalid or ambiguous data fails closed.
     */
    public static long parseLatest(byte[] raw) throws IOException {
        Objects.requireNonNull(raw, "raw");
        if (raw.length == 0) {
            throw new IOException("Empty native repository version report");
        }
        if (startsWith(raw, 0xEF, 0xBB, 0xBF)
                || startsWith(raw, 0xFF, 0xFE)
                || startsWith(raw, 0xFE, 0xFF)) {
            return parseLatest(TextReportDecoder.decode(raw));
        }
        Long result = null;
        for (Charset charset : new Charset[] {
                StandardCharsets.UTF_8, StandardCharsets.UTF_16LE,
                Charset.forName("windows-1251") }) {
            try {
                String decoded = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
                long candidate = parseLatest(decoded);
                if (result != null && result != candidate) {
                    throw new IOException("Ambiguous native repository report encoding");
                }
                result = candidate;
            } catch (CharacterCodingException ignored) {
                // This encoding is not valid for this byte sequence.
            } catch (IOException invalidReport) {
                if ("Ambiguous native repository report encoding".equals(
                        invalidReport.getMessage())) {
                    throw invalidReport;
                }
                // Candidate did not pass full 1C history report validation.
            }
        }
        if (result == null) {
            throw new IOException("Cannot decode/validate native repository report");
        }
        return result;
    }

    private static boolean startsWith(byte[] bytes, int a, int b) {
        return bytes.length >= 2
            && (bytes[0] & 255) == a && (bytes[1] & 255) == b;
    }

    private static boolean startsWith(byte[] bytes, int a, int b, int c) {
        return bytes.length >= 3 && (bytes[0] & 255) == a
            && (bytes[1] & 255) == b && (bytes[2] & 255) == c;
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
        return parseLatest(report.bytes());
    }
}
